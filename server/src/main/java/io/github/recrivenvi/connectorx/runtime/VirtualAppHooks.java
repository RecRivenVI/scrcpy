package io.github.recrivenvi.connectorx.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;
import org.json.JSONObject;

/** ConnectorX-only hooks; absent environment leaves the original scrcpy behavior intact. */
public final class VirtualAppHooks {
    public static final String STATUS_PREFIX = "CONNECTORX_STATUS ";
    private static final String PACKAGE = System.getenv("CONNECTORX_APP_PACKAGE");
    private static boolean launched;
    private static boolean refreshApplied;
    private static int launchResult = -1;
    private static int activityDisplayId = -1;
    private static float requestedRate() {
        String value = System.getenv("CONNECTORX_REFRESH_RATE");
        return value == null ? 60 : Float.parseFloat(value);
    }
    public static VirtualDisplay create(DisplayManager dm, String name, int width, int height, int dpi, Surface surface, int flags) throws Exception {
        if (PACKAGE != null && Build.VERSION.SDK_INT >= 34) {
            // Kept reflective like scrcpy's wrappers: API availability differs across Android releases.
            try {
                Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
                Object builder = builderClass.getConstructor(String.class, int.class, int.class, int.class)
                        .newInstance(name, width, height, dpi);
                builderClass.getMethod("setFlags", int.class).invoke(builder, flags);
                builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
                builderClass.getMethod("setRequestedRefreshRate", float.class).invoke(builder, requestedRate());
                Object config = builderClass.getMethod("build").invoke(builder);
                VirtualDisplay vd = (VirtualDisplay) dm.getClass().getMethod("createVirtualDisplay", config.getClass()).invoke(dm, config);
                refreshApplied = true;
                return vd;
            } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
                Ln.w("Requested refresh rate unavailable; using platform default");
            }
        }
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
    }
    @SuppressWarnings("deprecation")
    public static synchronized void onDisplayReady(VirtualDisplay vd) {
        if (PACKAGE == null) return;
        try {
            Display display = vd.getDisplay();
            if (!launched) {
                launched = true;
                launchResult = Device.startApp(PACKAGE, display.getDisplayId(), false);
                if (launchResult < 0) throw new IllegalStateException("Android 拒绝启动应用，result=" + launchResult);
                // Confirm the Activity is on the requested display, not silently brought to the physical screen.
                ActivityManager am = (ActivityManager) FakeContext.get().getSystemService(Context.ACTIVITY_SERVICE);
                long deadline = SystemClock.elapsedRealtime() + 2500;
                do {
                    for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
                        if ((task.topActivity != null && PACKAGE.equals(task.topActivity.getPackageName()))
                                || (task.baseActivity != null && PACKAGE.equals(task.baseActivity.getPackageName()))) {
                            activityDisplayId = task.getClass().getField("displayId").getInt(task);
                            if (activityDisplayId == display.getDisplayId()) break;
                        }
                    }
                    if (activityDisplayId == display.getDisplayId()) break;
                    SystemClock.sleep(100);
                } while (SystemClock.elapsedRealtime() < deadline);
                if (activityDisplayId != display.getDisplayId()) {
                    throw new IllegalStateException("应用未确认进入虚拟显示（实际=" + activityDisplayId + "，目标=" + display.getDisplayId() + "）");
                }
            }
            DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
            JSONObject state = new JSONObject().put("state", "ready").put("package", PACKAGE)
                    .put("displayId", display.getDisplayId()).put("activityDisplayId", activityDisplayId)
                    .put("width", metrics.widthPixels).put("height", metrics.heightPixels).put("dpi", metrics.densityDpi)
                    .put("rotation", display.getRotation()).put("refreshRate", display.getRefreshRate())
                    .put("requestedRefreshRate", requestedRate()).put("refreshRequestApplied", refreshApplied)
                    .put("launchResult", launchResult);
            Ln.i(STATUS_PREFIX + state);
        } catch (Exception e) {
            try { Ln.i(STATUS_PREFIX + new JSONObject().put("state", "failed").put("error", e.getMessage())); }
            catch (Exception ignored) {}
            throw new IllegalStateException("ConnectorX 虚拟应用启动失败", e);
        }
    }
    private VirtualAppHooks() {}
}
