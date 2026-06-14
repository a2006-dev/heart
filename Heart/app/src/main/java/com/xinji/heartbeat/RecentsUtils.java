package com.xinji.heartbeat;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

public class RecentsUtils {
    private static final String PREF_KEY = "hide_from_recents";
    private static final Handler H = new Handler(Looper.getMainLooper());

    public static void setHideFromRecents(Activity activity, boolean hide) {
        activity.getSharedPreferences("app_settings", Activity.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, hide).apply();
        applyRecentsState(activity, hide);

        H.postDelayed(() -> applyRecentsState(activity, hide), 500);
        H.postDelayed(() -> applyRecentsState(activity, hide), 2000);
    }

    public static boolean isHideFromRecents(Context context) {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false);
    }

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
