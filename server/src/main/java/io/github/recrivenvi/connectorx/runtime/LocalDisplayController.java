package io.github.recrivenvi.connectorx.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.wrappers.DisplayManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.WindowManager;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Local VD/input primitives. This fork deliberately has no dependency on ConnectorX protocol. */
public final class LocalDisplayController implements AutoCloseable {
    private static final int PUBLIC=1, PRESENTATION=2, OWN_CONTENT_ONLY=8;
    private static final int SUPPORTS_TOUCH=1<<6, ROTATES_WITH_CONTENT=1<<7, DESTROY_CONTENT=1<<8;
    private static final int SYSTEM_DECORATIONS=1<<9, TRUSTED=1<<10, OWN_DISPLAY_GROUP=1<<11;
    private static final int ALWAYS_UNLOCKED=1<<12, TOUCH_FEEDBACK_DISABLED=1<<13, OWN_FOCUS=1<<14, DEVICE_DISPLAY_GROUP=1<<15;
    private static final int DEVICE_ID=-1, MAX_POINTERS=10;
    private final Object inputLock=new Object();
    private final int width,height,dpi,displayId;
    private final String packageName;
    private final VirtualDisplay display;
    private final boolean followPhysicalRequested;
    private final int imePolicyReported;
    private final HandlerThread scrollThread;
    private final Handler scrollHandler;
    private final DisplayManager.DisplayListenerHandle displayListener;
    private final WheelTimeline timeline=new WheelTimeline();
    private final Runnable scrollTick=this::scrollTick;
    private final long[] externalIds=new long[MAX_POINTERS];
    private final MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[MAX_POINTERS];
    private final MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[MAX_POINTERS];
    private final MotionEvent.PointerProperties[] wheelProperties={new MotionEvent.PointerProperties()};
    private final MotionEvent.PointerCoords[] wheelCoords={new MotionEvent.PointerCoords()};
    private final Set<Integer> pressedKeys=new HashSet<>();
    private int pointerCount;
    private long downTimeNanos,lastTouchTimeNanos;
    private boolean closed,restarting,launchInFlight,released,surfaceAttached;
    private float requestedRate,effectiveRate,schedulerRate;
    private int scrollDirection;
    private float scrollUnitsPerSecond=WheelTimeline.DEFAULT_UNITS_PER_SECOND;
    private long inputEvents,injectFailures,injectDurationNanos,injectTotalDurationNanos,injectMaxDurationNanos;
    private long inputSampleAgeNanos,inputMaxSampleAgeNanos,touchFrames,rejectedTouchFrames;
    private long scrollTicks,scrollLateTicks,scrollMaxLatenessNanos,scrollSkippedTicks;
    private long lastScrollTimeNanos,scrollMaxIntervalNanos;

    public LocalDisplayController(String packageName,int width,int height,int dpi,float refreshRate,boolean decorations,Surface surface) throws Exception {
        this.packageName=packageName;this.width=width;this.height=height;this.dpi=dpi;requestedRate=refreshRate;
        for(int i=0;i<MAX_POINTERS;i++) {
            properties[i]=new MotionEvent.PointerProperties();
            properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();
        }
        wheelProperties[0].id=0;wheelProperties[0].toolType=MotionEvent.TOOL_TYPE_MOUSE;
        int flags=PUBLIC|PRESENTATION|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|DESTROY_CONTENT;
        if(decorations)flags|=SYSTEM_DECORATIONS;
        if(Build.VERSION.SDK_INT>=AndroidVersions.API_33_ANDROID_13) {
            flags|=TRUSTED|OWN_DISPLAY_GROUP|TOUCH_FEEDBACK_DISABLED;
            // SystemUI hides secondary navigation bars on lock, but restores only displays
            // with a KeyguardPresentation. ALWAYS_UNLOCKED skips that presentation, leaving
            // a decorated display's navigation bar GONE after unlock (AOSP 16 / shennong).
            if(!decorations)flags|=ALWAYS_UNLOCKED;
            if(Build.VERSION.SDK_INT>=AndroidVersions.API_34_ANDROID_14)flags|=OWN_FOCUS|DEVICE_DISPLAY_GROUP;
        }
        // Requested rate is fixed at VD creation on Android 13..16. Zero leaves pacing to Android.
        display=VirtualAppHooks.createLocal("ConnectorX local",width,height,dpi,surface,flags);
        displayId=display.getDisplay().getDisplayId();
        followPhysicalRequested=VirtualAppHooks.refreshRequestApplied(display);
        try {
            // The native keyboard belongs to the virtual display, alongside the target app.
            // Android handles its real Insets; neither the VD nor the outer preview is resized.
            ServiceManager.getWindowManager().setDisplayImePolicy(displayId,WindowManager.DISPLAY_IME_POLICY_LOCAL);
            imePolicyReported=ServiceManager.getWindowManager().getDisplayImePolicy(displayId);
            surfaceAttached=surface!=null;
            effectiveRate=display.getDisplay().getRefreshRate();
            schedulerRate=WheelTimeline.clampRate(effectiveRate);
        } catch(RuntimeException | Error failure) {display.release();throw failure;}
        scrollThread=new HandlerThread("connectorx-local-wheel");
        try {
            scrollThread.start();scrollHandler=new Handler(scrollThread.getLooper());
            displayListener=ServiceManager.getDisplayManager().registerDisplayListener(id -> {
                if(id==displayId||id==Display.DEFAULT_DISPLAY) {
                    synchronized(inputLock) {if(!closed)refreshRateLocked();}
                }
            },scrollHandler);
        } catch(RuntimeException | Error failure) {
            scrollThread.quitSafely();display.release();throw failure;
        }
    }

    private void ensureOpenLocked() {if(closed)throw new IllegalStateException("本机应用会话已结束");}
    private boolean inputAvailableLocked() {return !closed&&!restarting&&surfaceAttached;}

    /** Stop input immediately, even if the framework thread is busy looking up another app. */
    public void prepareRestart() {
        synchronized(inputLock) {
            ensureOpenLocked();
            if(restarting||launchInFlight)throw new IllegalStateException("应用正在启动或重启");
            restarting=true;cancelInputLocked();
        }
    }

    public String start(boolean forceStop) throws Exception {
        synchronized(inputLock) {
            ensureOpenLocked();
            if(launchInFlight)throw new IllegalStateException("应用正在启动或重启");
            launchInFlight=true;restarting=true;cancelInputLocked();
        }
        try {
            // No input lock over package resolution, ActivityManager start or task polling.
            int result=Device.startApp(packageName,displayId,forceStop);
            if(result<0)throw new IllegalStateException("Android 拒绝启动应用，result="+result);
            return describe(result);
        } finally {
            synchronized(inputLock) {
                launchInFlight=false;restarting=false;
                // Keep the VD alive during launch so a racing close cannot redirect launch to the main display.
                if(closed)releaseLocked();
            }
        }
    }

    public void abandonRestart() {synchronized(inputLock) {if(!launchInFlight)restarting=false;}}

    @SuppressWarnings("deprecation")
    private String describe(int launchResult) throws Exception {
        ActivityManager am=(ActivityManager)FakeContext.get().getSystemService(Context.ACTIVITY_SERVICE);
        int activityDisplay=-1;long deadline=SystemClock.elapsedRealtime()+3000;
        do {
            synchronized(inputLock) {ensureOpenLocked();}
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("本机应用启动已取消");
            for(ActivityManager.RunningTaskInfo task:am.getRunningTasks(100)) {
                if((task.topActivity!=null&&packageName.equals(task.topActivity.getPackageName()))||
                        (task.baseActivity!=null&&packageName.equals(task.baseActivity.getPackageName()))) {
                    int candidate=task.getClass().getField("displayId").getInt(task);
                    if(candidate==displayId){activityDisplay=candidate;break;}
                }
            }
            if(activityDisplay==displayId)break;
            SystemClock.sleep(50);
        } while(SystemClock.elapsedRealtime()<deadline);
        if(activityDisplay!=displayId)throw new IllegalStateException("应用未进入本机虚拟显示");
        synchronized(inputLock) {
            ensureOpenLocked();refreshRateLocked();
            DisplayMetrics metrics=new DisplayMetrics();display.getDisplay().getRealMetrics(metrics);
            return performanceLocked().put("state","ready").put("restarting",false).put("directSurface",true).put("package",packageName)
                    .put("activityDisplayId",activityDisplay).put("width",metrics.widthPixels).put("height",metrics.heightPixels)
                    .put("dpi",metrics.densityDpi).put("rotation",display.getDisplay().getRotation()).put("launchResult",launchResult).toString();
        }
    }

    public void setSurface(Surface surface) {
        synchronized(inputLock) {
            ensureOpenLocked();cancelInputLocked();
            display.setSurface(surface);surfaceAttached=surface!=null;refreshRateLocked();
        }
    }
    public void clearSurface() {
        synchronized(inputLock) {
            ensureOpenLocked();surfaceAttached=false;cancelInputLocked();display.setSurface(null);
        }
    }

    private int pointerIndex(long id) {for(int i=0;i<pointerCount;i++)if(externalIds[i]==id)return i;return -1;}
    private void addPointer(long external,float x,float y,float pressure) {
        int used=0;for(int i=0;i<pointerCount;i++)used|=1<<properties[i].id;
        externalIds[pointerCount]=external;properties[pointerCount].id=Integer.numberOfTrailingZeros(~used);
        coords[pointerCount].clear();coords[pointerCount].x=x;coords[pointerCount].y=y;
        coords[pointerCount].pressure=pressure;coords[pointerCount].size=1f;pointerCount++;
    }
    private void removePointer(int index) {
        MotionEvent.PointerProperties prop=properties[index];MotionEvent.PointerCoords coord=coords[index];
        int tail=pointerCount-index-1;
        System.arraycopy(externalIds,index+1,externalIds,index,tail);
        System.arraycopy(properties,index+1,properties,index,tail);
        System.arraycopy(coords,index+1,coords,index,tail);
        pointerCount--;properties[pointerCount]=prop;coords[pointerCount]=coord;
    }

    private boolean injectMotion(MotionEvent event,long sampleNanos) {
        long begin=System.nanoTime();boolean accepted=false;
        inputSampleAgeNanos=Math.max(0,begin-sampleNanos);
        inputMaxSampleAgeNanos=Math.max(inputMaxSampleAgeNanos,inputSampleAgeNanos);
        try {accepted=Device.injectEvent(event,displayId,Device.INJECT_MODE_ASYNC);return accepted;}
        catch(RuntimeException failure) {return false;}
        finally {
            long duration=System.nanoTime()-begin;
            inputEvents++;if(!accepted)injectFailures++;
            injectDurationNanos=duration;injectTotalDurationNanos+=duration;injectMaxDurationNanos=Math.max(injectMaxDurationNanos,duration);
            // ASYNC controls dispatch mode; the synchronous Binder transaction has copied the event.
            event.recycle();
        }
    }
    private boolean emitTouch(int action,int actionIndex,long sampleNanos) {
        if(pointerCount==0)return true;
        if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP)
            action|=actionIndex<<MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        MotionEvent event=MotionEvent.obtain(downTimeNanos/1_000_000L,sampleNanos/1_000_000L,action,pointerCount,
                properties,coords,0,0,1f,1f,DEVICE_ID,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        lastTouchTimeNanos=sampleNanos;
        return injectMotion(event,sampleNanos);
    }

    /** One event per accepted sample. Wire decoding and payload validation belong to the caller. */
    public void injectTouch(int action,int changedId,long eventTimeNanos,long gestureDownTimeNanos,int[] ids,float[] xs,float[] ys) {
        synchronized(inputLock) {
            if(!inputAvailableLocked())return;
            touchFrames++;
            if(action==MotionEvent.ACTION_CANCEL) {cancelTouchesLocked(eventTimeNanos);return;}
            if(ids==null||xs==null||ys==null||ids.length>MAX_POINTERS||xs.length!=ids.length||ys.length!=ids.length
                    ||eventTimeNanos<gestureDownTimeNanos||gestureDownTimeNanos<0) {rejectedTouchFrames++;return;}
            int changed=-1;
            for(int i=0;i<ids.length;i++) {
                if(!Float.isFinite(xs[i])||!Float.isFinite(ys[i])) {rejectedTouchFrames++;return;}
                for(int j=0;j<i;j++)if(ids[j]==ids[i]) {rejectedTouchFrames++;return;}
                if(ids[i]==changedId)changed=i;
            }
            boolean adding=action==MotionEvent.ACTION_DOWN;
            if((!adding&&action!=MotionEvent.ACTION_MOVE&&action!=MotionEvent.ACTION_UP)
                    ||ids.length!=pointerCount+(adding?1:0)
                    ||(pointerCount>0&&(eventTimeNanos<lastTouchTimeNanos||gestureDownTimeNanos!=downTimeNanos))
                    ||(adding&&(changed<0||pointerIndex(changedId)>=0))
                    ||(action==MotionEvent.ACTION_UP&&(changed<0||pointerIndex(changedId)<0))) {rejectedTouchFrames++;return;}
            // Partial MOVE/UP must never retain stale coordinates or silently invent/drop pointers.
            for(int i=0;i<ids.length;i++)if(!(adding&&i==changed)&&pointerIndex(ids[i])<0) {rejectedTouchFrames++;return;}
            if(adding&&pointerCount==0) {
                if(xs[changed]<0||ys[changed]<0||xs[changed]>=width||ys[changed]>=height){rejectedTouchFrames++;return;}
            }
            for(int i=0;i<ids.length;i++) {
                int index=pointerIndex(ids[i]);
                if(index>=0) {coords[index].x=xs[i];coords[index].y=ys[i];coords[index].pressure=1f;}
            }
            int encoded=action,index=0;
            if(adding) {
                if(pointerCount==0)downTimeNanos=gestureDownTimeNanos;
                addPointer(changedId,xs[changed],ys[changed],1f);index=pointerCount-1;
                encoded=pointerCount==1?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_POINTER_DOWN;
            } else if(action==MotionEvent.ACTION_UP) {
                index=pointerIndex(changedId);coords[index].pressure=0f;
                encoded=pointerCount==1?MotionEvent.ACTION_UP:MotionEvent.ACTION_POINTER_UP;
            }
            if(!emitTouch(encoded,index,eventTimeNanos)) {cancelTouchesLocked();return;}
            if(action==MotionEvent.ACTION_UP)removePointer(index);
        }
    }

    private void legacyTouch(ByteBuffer b) {
        int action=b.get()&255;long external=b.getLong();int x=b.getInt(),y=b.getInt(),sourceW=b.getShort()&0xffff,sourceH=b.getShort()&0xffff;
        float pressure=(b.getShort()&0xffff)/65535f;b.getInt();b.getInt();
        if(sourceW==0||sourceH==0)return;
        if(action==MotionEvent.ACTION_CANCEL) {cancelTouchesLocked();return;}
        float targetX=x*(float)width/sourceW,targetY=y*(float)height/sourceH;
        int index=pointerIndex(external);long now=System.nanoTime();
        if(action==MotionEvent.ACTION_DOWN) {
            if(index>=0||pointerCount==MAX_POINTERS)return;
            if(pointerCount==0)downTimeNanos=now;
            addPointer(external,targetX,targetY,pressure);
            if(!emitTouch(pointerCount==1?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_POINTER_DOWN,pointerCount-1,now))cancelTouchesLocked();
        } else if(index>=0) {
            coords[index].x=targetX;coords[index].y=targetY;coords[index].pressure=pressure;
            if(action==MotionEvent.ACTION_MOVE) {if(!emitTouch(action,0,now))cancelTouchesLocked();}
            else if(action==MotionEvent.ACTION_UP) {
                if(emitTouch(pointerCount==1?MotionEvent.ACTION_UP:MotionEvent.ACTION_POINTER_UP,index,now))removePointer(index);
                else cancelTouchesLocked();
            }
        }
    }

    private boolean emitScroll(float x,float y,float horizontal,float vertical,int buttons,long sampleNanos) {
        MotionEvent.PointerCoords coord=wheelCoords[0];coord.clear();coord.x=x;coord.y=y;
        coord.setAxisValue(MotionEvent.AXIS_HSCROLL,horizontal);coord.setAxisValue(MotionEvent.AXIS_VSCROLL,vertical);
        long millis=sampleNanos/1_000_000L;
        return injectMotion(MotionEvent.obtain(millis,millis,MotionEvent.ACTION_SCROLL,1,wheelProperties,wheelCoords,
                0,buttons,1f,1f,DEVICE_ID,0,InputDevice.SOURCE_MOUSE,0),sampleNanos);
    }
    private void injectKey(int action,int key,int repeat,int meta) {
        long begin=System.nanoTime();boolean accepted=false;
        // Keep even an uncertain DOWN until an UP succeeds or lifecycle cleanup attempts release.
        if(action==KeyEvent.ACTION_DOWN)pressedKeys.add(key);
        inputSampleAgeNanos=0;
        try {accepted=Device.injectKeyEvent(action,key,repeat,meta,displayId,Device.INJECT_MODE_ASYNC);}
        catch(RuntimeException ignored) { }
        finally {
            long duration=System.nanoTime()-begin;inputEvents++;if(!accepted)injectFailures++;
            injectDurationNanos=duration;injectTotalDurationNanos+=duration;injectMaxDurationNanos=Math.max(injectMaxDurationNanos,duration);
        }
        if(action==KeyEvent.ACTION_UP&&accepted)pressedKeys.remove(key);
    }
    public void inject(byte[] message) {
        synchronized(inputLock) {
            if(!inputAvailableLocked())return;
            ByteBuffer b=ByteBuffer.wrap(message);int type=b.get()&255;
            if(type==0) {int action=b.get()&255,key=b.getInt(),repeat=b.getInt(),meta=b.getInt();injectKey(action,key,repeat,meta);}
            else if(type==2)legacyTouch(b);
            else if(type==3) {
                int x=b.getInt(),y=b.getInt(),sourceW=b.getShort()&0xffff,sourceH=b.getShort()&0xffff;
                float horizontal=b.getShort()/2047f,vertical=b.getShort()/2047f;int buttons=b.getInt();
                if(sourceW!=0&&sourceH!=0)emitScroll(x*(float)width/sourceW,y*(float)height/sourceH,horizontal,vertical,buttons,System.nanoTime());
            }
        }
    }

    /** Touch CANCEL intentionally leaves the independently controlled wheel running. */
    public void cancelTouches() {synchronized(inputLock) {cancelTouchesLocked();}}
    private void cancelTouchesLocked() {
        cancelTouchesLocked(System.nanoTime());
    }
    private void cancelTouchesLocked(long sampleNanos) {
        try {if(pointerCount>0)emitTouch(MotionEvent.ACTION_CANCEL,0,Math.max(sampleNanos,lastTouchTimeNanos));}
        finally {pointerCount=0;downTimeNanos=0;lastTouchTimeNanos=0;}
    }
    private void cancelInputLocked() {
        stopScrollLocked();cancelTouchesLocked();
        while(!pressedKeys.isEmpty()) {
            int key=pressedKeys.iterator().next();
            try {injectKey(KeyEvent.ACTION_UP,key,0,0);} finally {pressedKeys.remove(key);}
        }
    }

    /** direction is AXIS_VSCROLL sign (-1/0/+1); nonpositive speed selects the configured default rate. */
    public void setScroll(int direction,float unitsPerSecond) {
        if(direction < -1||direction > 1||!Float.isFinite(unitsPerSecond))throw new IllegalArgumentException("Invalid local scroll");
        synchronized(inputLock) {
            ensureOpenLocked();
            if(direction==0||!inputAvailableLocked()) {stopScrollLocked();return;}
            stopScrollLocked();refreshRateLocked();
            scrollDirection=direction;scrollUnitsPerSecond=unitsPerSecond>0?unitsPerSecond:WheelTimeline.DEFAULT_UNITS_PER_SECOND;
            timeline.start(System.nanoTime(),schedulerRate);lastScrollTimeNanos=0;scheduleScrollLocked();
        }
    }
    private void stopScrollLocked() {scrollDirection=0;lastScrollTimeNanos=0;scrollHandler.removeCallbacks(scrollTick);}
    private void scheduleScrollLocked() {
        // Handler uptime and nanoTime share CLOCK_MONOTONIC. Round up, never poll at 1ms.
        scrollHandler.removeCallbacks(scrollTick);
        scrollHandler.postAtTime(scrollTick,(timeline.nextDeadlineNanos()+999_999L)/1_000_000L);
    }
    private void scrollTick() {
        synchronized(inputLock) {
            if(scrollDirection==0||!inputAvailableLocked())return;
            long now=System.nanoTime();WheelTimeline.Tick tick=timeline.poll(now);
            if(tick!=null) {
                scrollTicks++;if(tick.latenessNanos>0)scrollLateTicks++;
                scrollMaxLatenessNanos=Math.max(scrollMaxLatenessNanos,tick.latenessNanos);scrollSkippedTicks+=tick.skippedTicks;
                if(lastScrollTimeNanos!=0)scrollMaxIntervalNanos=Math.max(scrollMaxIntervalNanos,now-lastScrollTimeNanos);
                lastScrollTimeNanos=now;
                if(!emitScroll(width/2f,height/2f,0,scrollDirection*tick.units(scrollUnitsPerSecond),0,now)) {stopScrollLocked();return;}
            }
            scheduleScrollLocked();
        }
    }

    public void updateRefreshRate(float rate) {
        if(!Float.isFinite(rate)||rate<0)throw new IllegalArgumentException("Invalid local refresh rate");
        synchronized(inputLock) {ensureOpenLocked();requestedRate=rate;refreshRateLocked();}
    }
    private void refreshRateLocked() {
        effectiveRate=display.getDisplay().getRefreshRate();
        float next=WheelTimeline.clampRate(effectiveRate);
        if(Float.compare(next,schedulerRate)!=0) {
            schedulerRate=next;
            if(scrollDirection!=0) {
                scrollHandler.removeCallbacks(scrollTick);timeline.start(System.nanoTime(),schedulerRate);
                lastScrollTimeNanos=0;scheduleScrollLocked();
            }
        }
    }
    private JSONObject performanceLocked() throws JSONException {
        return new JSONObject().put("displayId",displayId).put("width",width).put("height",height).put("dpi",dpi)
                .put("imePolicyRequested",WindowManager.DISPLAY_IME_POLICY_LOCAL).put("imePolicyReported",imePolicyReported)
                .put("closed",closed).put("restarting",restarting).put("surfaceAttached",surfaceAttached)
                .put("refreshRate",effectiveRate).put("effectiveRefreshRate",effectiveRate).put("requestedRefreshRate",requestedRate)
                .put("virtualDisplayRequestedRefreshRate",0).put("refreshRequestApplied",requestedRate==0&&followPhysicalRequested)
                .put("followPhysicalRequestApplied",followPhysicalRequested).put("refreshUpdateSupported",false)
                .put("refreshMode",followPhysicalRequested?"follow-physical":"platform-default")
                .put("schedulerRefreshRate",schedulerRate).put("displayListenerRegistered",displayListener!=null)
                .put("inputEvents",inputEvents).put("injectFailures",injectFailures).put("injectDurationNanos",injectDurationNanos)
                .put("injectTotalDurationNanos",injectTotalDurationNanos).put("injectMaxDurationNanos",injectMaxDurationNanos)
                .put("inputSampleAgeNanos",inputSampleAgeNanos)
                .put("inputMaxSampleAgeNanos",inputMaxSampleAgeNanos).put("touchFrames",touchFrames).put("rejectedTouchFrames",rejectedTouchFrames)
                .put("activePointers",pointerCount).put("activeKeys",pressedKeys.size())
                .put("scrollDirection",scrollDirection).put("scrollUnitsPerSecond",scrollUnitsPerSecond)
                .put("scrollTicks",scrollTicks).put("scrollLateTicks",scrollLateTicks).put("scrollMaxLatenessNanos",scrollMaxLatenessNanos)
                .put("scrollSkippedTicks",scrollSkippedTicks).put("scrollMaxIntervalNanos",scrollMaxIntervalNanos);
    }
    public String performance() {
        synchronized(inputLock) {
            try {if(!closed)refreshRateLocked();return performanceLocked().toString();}
            catch(JSONException failure) {throw new IllegalStateException("本机性能统计失败",failure);}
        }
    }
    private String topPackageOnDisplay() {
        ActivityManager am=(ActivityManager)FakeContext.get().getSystemService(Context.ACTIVITY_SERVICE);
        for(ActivityManager.RunningTaskInfo task:am.getRunningTasks(100)) {
            try {if(task.getClass().getField("displayId").getInt(task)==displayId&&task.topActivity!=null)return task.topActivity.getPackageName();}
            catch(ReflectiveOperationException unavailable){return null;}
        }
        return null;
    }
    public boolean hideIme() {
        synchronized(inputLock){if(!inputAvailableLocked())throw new IllegalStateException("本机应用当前不可交互");}
        String target=topPackageOnDisplay();
        if(target==null)throw new IllegalStateException("未找到虚拟屏上的输入目标");
        try {
            int uid=FakeContext.get().getPackageManager().getApplicationInfo(target,0).uid;
            return LocalImeController.hide(displayId,uid,()->{
                synchronized(inputLock){if(!inputAvailableLocked())return false;}
                return target.equals(topPackageOnDisplay());
            });
        } catch(Exception failure) {
            Throwable cause=failure instanceof java.lang.reflect.InvocationTargetException?failure.getCause():failure;
            throw new IllegalStateException("无法收起本机键盘："+cause.getMessage(),cause);
        }
    }
    private void releaseLocked() {if(!released) {released=true;display.release();}}
    @Override public void close() {
        boolean closing=false;
        try {
            synchronized(inputLock) {
                if(closed)return;
                closing=true;closed=true;surfaceAttached=false;
                try {cancelInputLocked();} finally {if(!launchInFlight)releaseLocked();}
            }
        } finally {
            if(closing) {
                try {if(displayListener!=null)ServiceManager.getDisplayManager().unregisterDisplayListener(displayListener);}
                finally {scrollThread.quitSafely();}
            }
        }
    }
}
