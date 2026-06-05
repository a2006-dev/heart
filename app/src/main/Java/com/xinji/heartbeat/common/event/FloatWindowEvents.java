package com.xinji.heartbeat.common.event;

/**
 * 悬浮窗相关的事件定义
 */
public class FloatWindowEvents {
    /**
     * 悬浮窗显示
     */
    public static class FloatWindowShownEvent extends Event {}

    /**
     * 悬浮窗隐藏
     */
    public static class FloatWindowHiddenEvent extends Event {}

    /**
     * 悬浮窗样式改变
     */
    public static class FloatWindowStyleChangedEvent extends Event {
        public int newStyle;
        
        public FloatWindowStyleChangedEvent(int newStyle) {
            this.newStyle = newStyle;
        }
    }

    /**
     * 悬浮窗锁定状态改变
     */
    public static class FloatWindowLockedEvent extends Event {
        public boolean locked;
        
        public FloatWindowLockedEvent(boolean locked) {
            this.locked = locked;
        }
    }
}
