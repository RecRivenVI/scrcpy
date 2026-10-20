package io.github.recrivenvi.connectorx.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A conservative, on-demand IMMS snapshot. Never infer ownership from a connected-client list. */
public final class LocalImeBinding {
    private static final Pattern CLIENT=Pattern.compile("(?m)^\\s*mCurClient=(null|ClientState\\{([^}\\r\\n]+)\\})\\s*$");
    private static final Pattern SHOWN=Pattern.compile("(?m)^\\s*mInputShown=(true|false)\\s*$");
    private static final Pattern UID=Pattern.compile("(?:^|\\s)mUid=(\\d+)(?:\\s|$)");
    private static final Pattern DISPLAY=Pattern.compile("(?:^|\\s)mSelfReportedDisplayId=(\\d+)(?:\\s|$)");
    public final int uid,displayId;
    public final boolean shown;
    private LocalImeBinding(int uid,int displayId,boolean shown){this.uid=uid;this.displayId=displayId;this.shown=shown;}
    public static LocalImeBinding parse(String dump) {
        Matcher client=CLIENT.matcher(dump),visible=SHOWN.matcher(dump);
        if(!client.find()||!visible.find())throw new IllegalStateException("系统未提供可识别的输入法绑定状态");
        String fields=client.group(2);boolean shown=Boolean.parseBoolean(visible.group(1));
        if(client.find()||visible.find())throw new IllegalStateException("输入法绑定状态不唯一，已取消操作");
        if(fields==null) {
            if(shown)throw new IllegalStateException("键盘显示但没有可确认的输入目标");
            return new LocalImeBinding(-1,-1,false);
        }
        Matcher uid=UID.matcher(fields),display=DISPLAY.matcher(fields);
        if(!uid.find()||!display.find())throw new IllegalStateException("系统未提供输入目标的显示或身份");
        int u=Integer.parseInt(uid.group(1)),d=Integer.parseInt(display.group(1));
        if(uid.find()||display.find())throw new IllegalStateException("输入目标字段不唯一，已取消操作");
        return new LocalImeBinding(u,d,shown);
    }
    public boolean belongsTo(int expectedDisplay,int expectedUid){return displayId==expectedDisplay&&uid==expectedUid;}
}
