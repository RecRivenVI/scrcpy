package io.github.recrivenvi.connectorx.runtime;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Shell-only IME dismissal. Does not inject Back, create Insets, or retain editor data. */
public final class LocalImeController {
    private static final int MAX_DUMP_BYTES=512*1024;
    private static IBinder service(String name)throws Exception {
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        if(binder==null)throw new IllegalStateException("系统服务不可用："+name);
        return binder;
    }
    private static String snapshot(long deadline)throws Exception {
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        try(ParcelFileDescriptor input=pipe[0];ParcelFileDescriptor output=pipe[1]) {
            // One-way dump and bounded polling keep an unresponsive system from blocking indefinitely.
            service("input_method").dumpAsync(output.getFileDescriptor(),new String[0]);
            output.close();
            StructPollfd fd=new StructPollfd();fd.fd=input.getFileDescriptor();fd.events=(short)OsConstants.POLLIN;
            byte[] buffer=new byte[4096];ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            while(true) {
                long remaining=deadline-SystemClock.elapsedRealtime();
                if(remaining<=0)throw new IOException("读取输入法状态超时");
                if(Os.poll(new StructPollfd[]{fd},(int)remaining)==0)throw new IOException("读取输入法状态超时");
                if((fd.revents&(OsConstants.POLLERR|OsConstants.POLLNVAL))!=0)throw new IOException("读取输入法状态失败");
                int count=Os.read(fd.fd,buffer,0,buffer.length);
                if(count==0)break;
                if(bytes.size()+count>MAX_DUMP_BYTES)throw new IOException("输入法状态超出诊断上限");
                bytes.write(buffer,0,count);
            }
            // Not logged, persisted or sent over the network; only current UID/display/visibility is used.
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    /** @return true after requested dismissal is confirmed by IMMS; false if already hidden. */
    public static boolean hide(int displayId,int uid,BooleanSupplier stillActive)throws Exception {
        if(displayId<=0||uid<0||uid/100000!=android.os.Process.myUid()/100000)
            throw new IllegalStateException("当前输入目标不属于可控制的本机用户或显示");
        LocalImeBinding binding=LocalImeBinding.parse(snapshot(SystemClock.elapsedRealtime()+1500));
        if(!stillActive.getAsBoolean())throw new IllegalStateException("本机会话已离开前台或输入目标已变化");
        if(!binding.shown)return false;
        if(!binding.belongsTo(displayId,uid))throw new IllegalStateException("当前键盘不属于此本机应用，已取消操作");
        IBinder binder=service("statusbar");
        Object manager=Class.forName("com.android.internal.statusbar.IStatusBarService$Stub")
                .getMethod("asInterface",IBinder.class).invoke(null,binder);
        Method hide;
        try {hide=manager.getClass().getMethod("hideCurrentInputMethodForBubbles",int.class);}
        catch(NoSuchMethodException legacy){hide=manager.getClass().getMethod("hideCurrentInputMethodForBubbles");}
        if(!stillActive.getAsBoolean())throw new IllegalStateException("本机会话已离开前台或输入目标已变化");
        // This is a current-user system API. Snapshot + revalidation narrow, but cannot eliminate,
        // an external focus race. The physical host's Insets cannot observe a virtual-screen IME.
        if(hide.getParameterCount()==0)hide.invoke(manager);else hide.invoke(manager,displayId);
        // Bounded confirmation only after the user's hide request; no idle/background polling.
        long deadline=SystemClock.elapsedRealtime()+2500;
        while(SystemClock.elapsedRealtime()<deadline) {
            binding=LocalImeBinding.parse(snapshot(Math.min(deadline,SystemClock.elapsedRealtime()+1500)));
            if(!stillActive.getAsBoolean())throw new IllegalStateException("输入目标已变化，本次返回已取消");
            if(!binding.shown)return true;
            if(!binding.belongsTo(displayId,uid))throw new IllegalStateException("键盘输入目标已变化，本次返回已取消");
            SystemClock.sleep(Math.min(100,Math.max(0,deadline-SystemClock.elapsedRealtime())));
        }
        throw new IOException("键盘尚未确认收起，本次返回没有发送给目标应用");
    }
}
