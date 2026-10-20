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
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** ConnectorX-only hooks; absent environment leaves the original scrcpy behavior intact. */
public final class VirtualAppHooks {
    public static final String STATUS_PREFIX = "CONNECTORX_STATUS ";
    private static final String PACKAGE = System.getenv("CONNECTORX_APP_PACKAGE");
    private static boolean launched;
    private static final Map<VirtualDisplay, Boolean> REFRESH_REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static int launchResult = -1;
    private static int activityDisplayId = -1;
    private static float requestedRate() {
        String value = System.getenv("CONNECTORX_REFRESH_RATE");
        return value == null ? 60 : Float.parseFloat(value);
    }
    public static VirtualDisplay create(DisplayManager dm, String name, int width, int height, int dpi, Surface surface, int flags) throws Exception {
        return create(dm,name,width,height,dpi,surface,flags,PACKAGE==null?0:requestedRate());
    }
    public static VirtualDisplay create(DisplayManager dm, String name, int width, int height, int dpi, Surface surface, int flags, float refreshRate) throws Exception {
        return createWithRate(dm,name,width,height,dpi,surface,flags,refreshRate,false);
    }
    private static VirtualDisplay createWithRate(DisplayManager dm, String name, int width, int height, int dpi, Surface surface, int flags,
                                                float refreshRate, boolean followPhysical) throws Exception {
        if (Float.isFinite(refreshRate) && (refreshRate >= 24 || (followPhysical && refreshRate == 0)) && Build.VERSION.SDK_INT >= 34) {
            // Kept reflective like scrcpy's wrappers: API availability differs across Android releases.
            try {
                Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
                Object builder = builderClass.getConstructor(String.class, int.class, int.class, int.class)
                        .newInstance(name, width, height, dpi);
                builderClass.getMethod("setFlags", int.class).invoke(builder, flags);
                builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
                builderClass.getMethod("setRequestedRefreshRate", float.class).invoke(builder, refreshRate);
                Object config = builderClass.getMethod("build").invoke(builder);
                VirtualDisplay vd = (VirtualDisplay) dm.getClass().getMethod("createVirtualDisplay", config.getClass()).invoke(dm, config);
                if (vd == null) throw new IllegalStateException("Virtual display creation returned null");
                REFRESH_REQUESTS.put(vd, true);
                return vd;
            } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
                Ln.w("Requested refresh rate unavailable; using platform default");
            }
        }
        VirtualDisplay vd = dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
        if (vd == null) throw new IllegalStateException("Virtual display creation returned null");
        REFRESH_REQUESTS.put(vd, false);
        return vd;
    }
    /** Android 16 exposes no in-place requested-refresh setter on VirtualDisplay or IDisplayManager.
     * Zero requests platform/physical pacing at creation without ever resizing or recreating the VD.
     * Older platforms fall back to their default; callers must report the measured Display rate.
     */
    public static VirtualDisplay createLocal(String name, int width, int height, int dpi, Surface surface, int flags) throws Exception {
        Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        return createWithRate(ctor.newInstance(FakeContext.get()), name, width, height, dpi, surface, flags, 0, true);
    }
    public static boolean refreshRequestApplied(VirtualDisplay display) {
        return Boolean.TRUE.equals(REFRESH_REQUESTS.get(display));
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
                    .put("requestedRefreshRate", requestedRate()).put("refreshRequestApplied", refreshRequestApplied(vd))
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
