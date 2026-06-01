package com.xinji.heartbeat.widget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * 悬浮窗管理器 — 独立管理 5 种样式的心率悬浮窗。
 *
 * 改进：
 * - 所有样式都正确设置 floatHrView，修复圆形/文字/迷你样式无法更新数值的 Bug
 * - 首次权限申请增加 onActivityResult 自动重试
 * - 位置记忆独立 SharedPreferences
 */
public class FloatWindowManager {
    private static final String PREFS_POS = "float_pos";
    private static final String PREFS_SETTINGS = "app_settings";

    public static final String[] STYLE_NAMES = {"简约文字", "科技胶囊", "圆形徽章", "心电脉搏", "超小迷你"};

    private final WeakReference<Activity> activityRef;
    private WindowManager wm;
    private View floatView;
    private TextView floatHrView;
    private WindowManager.LayoutParams floatParams;
    private boolean floatVisible = false;
    private boolean floatLocked = false;
    private boolean memoryEnabled = true;
    private int screenW, screenH;

    // 拖拽
    private int dragInitX, dragInitY;
    private float dragTouchX, dragTouchY;

    public FloatWindowManager(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
        getScreenSize();
    }

    public void setMemoryEnabled(boolean enabled) { this.memoryEnabled = enabled; }
    public boolean isMemoryEnabled() { return memoryEnabled; }

    public boolean isVisible() { return floatVisible; }
    public boolean isLocked() { return floatLocked; }

    public void setLocked(boolean locked) {
        this.floatLocked = locked;
        updateTouchable();
        savePref("float_locked", locked);
    }

    public View getFloatView() { return floatView; }

    // ===================== 生命周期 =====================

    public void show() {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            requestOverlayPermission(activity);
            return;
        }

        if (!floatVisible) {
            createFloatView(activity);
            if (floatView == null) {
                floatVisible = false;
                savePref("float_visible", false);
                return;
            }
            floatVisible = true;
            savePref("float_visible", true);
        }
    }

    public void hide() {
        if (floatVisible && floatView != null && wm != null) {
            try { wm.removeView(floatView); } catch (Exception ignored) {}
        }
        floatVisible = false;
    }

    public void hideByUser() {
        hide();
        savePref("float_visible", false);
    }

    public void toggle() {
        if (floatVisible) hideByUser();
        else show();
    }

    public void recreate() {
        boolean wasVisible = floatVisible;
        if (wasVisible) hide();
        floatView = null;
        floatHrView = null;
        if (wasVisible) show();
    }

    /** 更新心率数值 */
    public void updateHR(int hr) {
        if (floatHrView != null) {
            int style = getPrefInt("float_style", 0);
            if (style == 0) {
                // 简约文字样式：显示数字 + BPM
                floatHrView.setText(hr + " BPM");
            } else {
                floatHrView.setText(String.valueOf(hr));
            }
        }
    }

    /** 更新简约文字样式（带BPM后缀） */
    public void updateTextStyle(int hr) {
        if (floatHrView != null) {
            floatHrView.setText(hr + " BPM");
        }
    }

    /** 设置离线状态：显示 "--" 但保留悬浮窗不隐藏 */
    public void setOffline() {
        if (floatHrView != null) {
            floatHrView.setText("--");
        }
    }

    // ===================== 内部 =====================

    private void requestOverlayPermission(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(intent, 101);
        } catch (Exception e) {
            Toast.makeText(activity, "请在设置中手动开启「悬浮窗」权限", Toast.LENGTH_LONG).show();
            try {
                Intent detailIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(detailIntent);
            } catch (Exception ignored) {}
        }
    }

    private void createFloatView(Activity activity) {
        wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        int style = getPrefInt("float_style", 0);
        floatHrView = null;
        floatView = buildFloatViewByStyle(activity, style);

        if (floatView == null) return;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (floatLocked) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        floatParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT);

        floatParams.gravity = Gravity.TOP | Gravity.START;
        floatParams.x = getPrefInt("float_x", screenW - dp2px(activity, 100));
        floatParams.y = getPrefInt("float_y", dp2px(activity, 100));

        floatView.setOnTouchListener((v, event) -> {
            if (floatLocked || floatParams == null) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragInitX = floatParams.x; dragInitY = floatParams.y;
                    dragTouchX = event.getRawX(); dragTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    floatParams.x = dragInitX + (int) (event.getRawX() - dragTouchX);
                    floatParams.y = dragInitY + (int) (event.getRawY() - dragTouchY);
                    wm.updateViewLayout(floatView, floatParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (memoryEnabled) {
                        getPosPrefs(activity).edit()
                            .putInt("float_x", floatParams.x)
                            .putInt("float_y", floatParams.y)
                            .apply();
                    }
                    return true;
            }
            return false;
        });

        try {
            wm.addView(floatView, floatParams);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(activity, "悬浮窗开启失败，请检查权限设置", Toast.LENGTH_LONG).show();
            floatView = null;
        }
    }

    private View buildFloatViewByStyle(Activity activity, int style) {
        switch (style) {
            case 1: return buildPillStyle(activity);
            case 2: return buildCircleStyle(activity);
            case 3: return buildHeartStyle(activity);
            case 4: return buildMiniStyle(activity);
            default: return buildTextStyle(activity);
        }
    }

    // ---- 样式 0: 简约文字 ----
    private View buildTextStyle(Activity activity) {
        TextView v = new TextView(activity);
        v.setText("-- BPM");
        v.setTextColor(0xFFFF5D7C);
        v.setTextSize(20);
        v.setTypeface(null, Typeface.BOLD);
        v.setShadowLayer(3, 1, 1, 0xAA000000);
        v.setPadding(dp2px(activity, 8), dp2px(activity, 4), dp2px(activity, 8), dp2px(activity, 4));
        // 文字样式没有单独的数值区域，我们用整体 text 来更新
        floatHrView = v;
        return v;
    }

    // ---- 样式 1: 科技胶囊 ----
    private View buildPillStyle(Activity activity) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp2px(activity, 14), dp2px(activity, 8), dp2px(activity, 14), dp2px(activity, 8));

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xDD0A0E17);
        bg.setCornerRadius(dp2px(activity, 20));
        bg.setStroke(dp2px(activity, 2), 0x8800D4FF);
        v.setBackground(bg);
        v.setElevation(dp2px(activity, 6));

        TextView pulse = new TextView(activity);
        pulse.setText("●");
        pulse.setTextColor(0xFF00D4FF);
        pulse.setTextSize(10);
        LinearLayout.LayoutParams pulseLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pulseLp.rightMargin = dp2px(activity, 8);
        v.addView(pulse, pulseLp);

        TextView hr = new TextView(activity);
        hr.setText("--");
        hr.setTextColor(0xFFFFFFFF);
        hr.setTextSize(20);
        hr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.addView(hr);

        TextView unit = new TextView(activity);
        unit.setText(" bpm");
        unit.setTextColor(0x8800D4FF);
        unit.setTextSize(11);
        unit.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        unitLp.leftMargin = dp2px(activity, 4);
        unitLp.bottomMargin = dp2px(activity, 2);
        v.addView(unit, unitLp);

        floatHrView = hr;
        return v;
    }

    // ---- 样式 2: 圆形徽章 ----
    private View buildCircleStyle(Activity activity) {
        TextView v = new TextView(activity);
        v.setText("--");
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(16);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        int size = dp2px(activity, 56);
        v.setWidth(size);
        v.setHeight(size);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xDDFF5D7C);
        bg.setStroke(dp2px(activity, 2), 0xFFFFFFFF);
        v.setBackground(bg);

        floatHrView = v;
        return v;
    }

    // ---- 样式 3: 心电脉搏 ----
    private View buildHeartStyle(Activity activity) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp2px(activity, 10), dp2px(activity, 8), dp2px(activity, 10), dp2px(activity, 8));

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xDD0A0E17);
        bg.setCornerRadius(dp2px(activity, 12));
        bg.setStroke(dp2px(activity, 1), 0x66FF4466);
        v.setBackground(bg);
        v.setElevation(dp2px(activity, 4));

        TextView pulseIcon = new TextView(activity);
        pulseIcon.setText("❤️");
        pulseIcon.setTextSize(18);
        pulseIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconLp.bottomMargin = dp2px(activity, 4);
        v.addView(pulseIcon, iconLp);

        TextView hr = new TextView(activity);
        hr.setText("--");
        hr.setTextColor(0xFFFF5577);
        hr.setTextSize(22);
        hr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hr.setGravity(Gravity.CENTER);
        hr.setMinWidth(dp2px(activity, 40));
        v.addView(hr);

        TextView label = new TextView(activity);
        label.setText("脉搏");
        label.setTextColor(0xAAFF5577);
        label.setTextSize(9);
        label.setTypeface(null, Typeface.BOLD);
        v.addView(label);

        floatHrView = hr;
        return v;
    }

    // ---- 样式 4: 超小迷你 ----
    private View buildMiniStyle(Activity activity) {
        TextView v = new TextView(activity);
        v.setText("--");
        v.setTextColor(0xFFFF5D7C);
        v.setTextSize(10);
        v.setTypeface(null, Typeface.BOLD);
        v.setShadowLayer(1, 1, 1, 0xAA000000);
        v.setPadding(dp2px(activity, 3), dp2px(activity, 2), dp2px(activity, 3), dp2px(activity, 2));
        floatHrView = v;
        return v;
    }

    // ---- 更新可触摸状态 ----
    private void updateTouchable() {
        if (floatView == null || floatParams == null || wm == null) return;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (floatLocked) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        floatParams.flags = flags;
        try { wm.updateViewLayout(floatView, floatParams); } catch (Exception ignored) {}
    }

    // ---- 工具 ----
    private void getScreenSize() {
        Activity activity = activityRef.get();
        if (activity == null) return;
        try {
            android.graphics.Point s = new android.graphics.Point();
            activity.getWindowManager().getDefaultDisplay().getSize(s);
            screenW = s.x;
            screenH = s.y;
        } catch (Exception e) {
            screenW = 1080;
            screenH = 1920;
        }
    }

    private int dp2px(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    private SharedPreferences getPosPrefs(Context context) {
        return context.getSharedPreferences(PREFS_POS, Context.MODE_PRIVATE);
    }

    private void savePref(String key, boolean value) {
        Activity activity = activityRef.get();
        if (activity != null) {
            activity.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
        }
    }

    private int getPrefInt(String key, int def) {
        Activity activity = activityRef.get();
        if (activity != null) {
            return activity.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getInt(key, def);
        }
        return def;
    }

    /** 处理悬浮窗权限返回结果，自动重试 */
    public boolean handlePermissionResult(int requestCode, int resultCode) {
        if (requestCode == 101) {
            Activity activity = activityRef.get();
            if (activity != null && Settings.canDrawOverlays(activity)) {
                show();
                return true;
            }
        }
        return false;
    }
}