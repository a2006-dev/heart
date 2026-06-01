package com.xinji.heartbeat;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 最近任务列表隐藏工具
 * 使用 AppTask.setExcludeFromRecents 动态隐藏，不重启 App，不中断 Service
 * 加入延迟重试机制以兼容各厂商 ROM
 */
public class RecentsUtils {

    private static final String PREF_KEY = "hide_from_recents";
    private static final Handler H = new Handler(Looper.getMainLooper());

    public static void setHideFromRecents(Activity activity, boolean hide) {
        activity.getSharedPreferences("app_settings", Activity.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, hide).apply();
        applyRecentsState(activity, hide);
        // 延迟重试，兼容 MIUI/HarmonyOS 等
        H.postDelayed(() -> applyRecentsState(activity, hide), 500);
        H.postDelayed(() -> applyRecentsState(activity, hide), 2000);
    }

    public static boolean isHideFromRecents(Context context) {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false);
    }

    /** 应用最近任务隐藏状态 */
    public static void applyRecentsState(Context context, boolean hide) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    for (ActivityManager.AppTask task : am.getAppTasks()) {
                        task.setExcludeFromRecents(hide);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}