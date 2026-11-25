package com.xinji.heartbeat.widget;
import com.xinji.heartbeat.HeartRateRange;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.animation.ValueAnimator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public class FloatWindowManager {
    private static final String PREFS_POS = "float_pos";
    private static final String PREFS_SETTINGS = "app_settings";

    public static final String[] STYLE_NAMES = {"大字", "胶囊", "圆环", "心跳", "迷你"};
    private int floatBg, floatLabel, floatAccent;
    private void loadFloatColors(Activity activity) {
        floatBg = androidx.core.content.ContextCompat.getColor(activity, com.xinji.heartbeat.R.color.float_bg);
        floatLabel = androidx.core.content.ContextCompat.getColor(activity, com.xinji.heartbeat.R.color.float_label);
        floatAccent = androidx.core.content.ContextCompat.getColor(activity, com.xinji.heartbeat.R.color.float_accent);
    }

    private final WeakReference<Activity> activityRef;
    private WindowManager wm;
    private View floatView;
    private TextView floatHrView;
    private WindowManager.LayoutParams floatParams;
    private boolean floatVisible = false;
    private boolean floatLocked = false;
    private boolean memoryEnabled = true;
    private int screenW, screenH;

    private int dragInitX, dragInitY;
    private float dragTouchX, dragTouchY;
    private boolean dragMoved = false;
    private int touchSlop = 8;
    private boolean edgeAnimating = false;

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

    public void show() {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;
        loadFloatColors(activity);

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

    public void updateHR(int hr) {
        if (floatHrView != null) {
            int style = getPrefInt("float_style", 0);
            if (style == 0) {
                floatHrView.setText(hr + " BPM");
            } else {
                floatHrView.setText(String.valueOf(hr));
            }

            if (style == 3 && pulseIconView != null && HeartRateRange.isValid(hr)) {
                pulseIconView.animate().scaleX(1.3f).scaleY(1.3f).setDuration(80)
                    .withEndAction(() -> pulseIconView.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                    .start();
            }
        }
    }

    public void updateTextStyle(int hr) {
        if (floatHrView != null) {
            floatHrView.setText(hr + " BPM");
        }
    }

    public void setOffline() {
        if (floatHrView != null) {
            floatHrView.setText("--");
        }
    }

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
        this.floatLocked = getPrefBool("float_locked", false);
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
        applySavedPosition(activity);

        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        floatView.setOnTouchListener((v, event) -> {
            if (floatLocked || floatParams == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragInitX = floatParams.x;
                    dragInitY = floatParams.y;
                    dragTouchX = event.getRawX();
                    dragTouchY = event.getRawY();
                    dragMoved = false;
                    if (edgeAnimating) {
                        edgeAnimating = false;
                        v.animate().cancel();
                    }
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - dragTouchX;
                    float dy = event.getRawY() - dragTouchY;
                    if (!dragMoved && Math.hypot(dx, dy) > touchSlop) {
                        dragMoved = true;
                        v.setPressed(false);
                    }
                    if (dragMoved) {
                        moveToClamped(dragInitX + (int) dx, dragInitY + (int) dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (dragMoved) {
                        snapToEdge(activity);
                        savePosition(activity);
                    } else {
                        openMainActivity();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (dragMoved) {
                        snapToEdge(activity);
                        savePosition(activity);
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

    private View buildTextStyle(Activity activity) {
        TextView v = new TextView(activity);
        v.setText("-- BPM");
        v.setTextColor(floatAccent);
        v.setTextSize(20);
        v.setTypeface(null, Typeface.BOLD);
        v.setShadowLayer(3, 1, 1, 0xAA000000);
        v.setPadding(dp2px(activity, 8), dp2px(activity, 4), dp2px(activity, 8), dp2px(activity, 4));
        v.setMinWidth(dp2px(activity, 48));
        floatHrView = v;
        return v;
    }

    private View buildPillStyle(Activity activity) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp2px(activity, 14), dp2px(activity, 8), dp2px(activity, 14), dp2px(activity, 8));

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(floatBg);
        bg.setCornerRadius(dp2px(activity, 24));
        bg.setStroke(dp2px(activity, 1), (floatAccent & 0x00FFFFFF) | 0x33000000);
        v.setBackground(bg);
        v.setElevation(dp2px(activity, 6));

        TextView pulse = new TextView(activity);
        pulse.setText("●");
        pulse.setTextColor(floatAccent);
        pulse.setTextSize(10);
        LinearLayout.LayoutParams pulseLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pulseLp.rightMargin = dp2px(activity, 8);
        v.addView(pulse, pulseLp);

        TextView hr = new TextView(activity);
        hr.setText("--");
        hr.setTextColor(floatLabel);
        hr.setTextSize(20);
        hr.setTypeface(Typeface.DEFAULT_BOLD);
        hr.setMinWidth(dp2px(activity, 32));
        v.addView(hr);

        TextView unit = new TextView(activity);
        unit.setText(" bpm");
        unit.setTextColor((floatAccent & 0x00FFFFFF) | 0x99000000);
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

    private View buildCircleStyle(Activity activity) {
        TextView v = new TextView(activity);
        v.setText("--");
        v.setTextColor(floatLabel);
        v.setTextSize(18);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        int size = dp2px(activity, 64);
        v.setWidth(size);
        v.setHeight(size);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(floatAccent);
        bg.setStroke(dp2px(activity, 1), (floatLabel & 0x00FFFFFF) | 0x55000000);
        v.setBackground(bg);

        floatHrView = v;
        return v;
    }

    private TextView pulseIconView;

    private View buildHeartStyle(Activity activity) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp2px(activity, 10), dp2px(activity, 8), dp2px(activity, 10), dp2px(activity, 8));

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(floatBg);
        bg.setCornerRadius(dp2px(activity, 18));
        bg.setStroke(dp2px(activity, 1), (floatAccent & 0x00FFFFFF) | 0x33000000);
        v.setBackground(bg);
        v.setElevation(dp2px(activity, 4));

        pulseIconView = new TextView(activity);
        pulseIconView.setText("❤️");
        pulseIconView.setTextSize(18);
        pulseIconView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconLp.bottomMargin = dp2px(activity, 4);
        v.addView(pulseIconView, iconLp);

        TextView hr = new TextView(activity);
        hr.setText("--");
        hr.setTextColor(floatAccent);
        hr.setTextSize(14);
        hr.setTypeface(Typeface.DEFAULT_BOLD);
        hr.setGravity(Gravity.CENTER);
        hr.setSingleLine(true);
        hr.setMinWidth(dp2px(activity, 52));
        hr.setMaxWidth(dp2px(activity, 100));
        v.addView(hr);

        TextView label = new TextView(activity);
        label.setText("脉搏");
        label.setTextColor((floatAccent & 0x00FFFFFF) | 0xAA000000);
        label.setTextSize(8);
        label.setTypeface(null, Typeface.BOLD);
        v.addView(label);

        floatHrView = hr;
        return v;
    }

    private View buildMiniStyle(Activity activity) {
        TextView v = new TextView(activity);
        v.setText("--");
        v.setTextColor(floatAccent);
        v.setTextSize(10);
        v.setTypeface(null, Typeface.BOLD);
        v.setShadowLayer(1, 1, 1, 0xAA000000);
        v.setPadding(dp2px(activity, 3), dp2px(activity, 2), dp2px(activity, 3), dp2px(activity, 2));
        floatHrView = v;
        return v;
    }

    private void updateTouchable() {
        if (floatView == null || floatParams == null || wm == null) return;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (floatLocked) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        floatParams.flags = flags;
        floatParams.alpha = floatLocked ? 1.0f : 1.0f;
        try { wm.updateViewLayout(floatView, floatParams); } catch (Exception ignored) {}
    }

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

    private int viewW() {
        if (floatView == null) return 0;
        int w = floatView.getWidth();
        return w > 0 ? w : floatView.getMeasuredWidth();
    }

    private int viewH() {
        if (floatView == null) return 0;
        int h = floatView.getHeight();
        return h > 0 ? h : floatView.getMeasuredHeight();
    }

    private void moveToClamped(int x, int y) {
        if (floatParams == null || wm == null || floatView == null) return;
        int w = viewW();
        int h = viewH();
        int maxX = Math.max(0, screenW - w);
        int maxY = Math.max(0, screenH - h);
        floatParams.x = Math.max(0, Math.min(x, maxX));
        floatParams.y = Math.max(0, Math.min(y, maxY));
        try {
            wm.updateViewLayout(floatView, floatParams);
        } catch (IllegalArgumentException ignored) {}
    }

    private void snapToEdge(Activity activity) {
        if (activity == null) return;
        if (floatParams == null || wm == null || floatView == null) return;
        int w = viewW();
        int maxX = Math.max(0, screenW - w);
        final int target = floatParams.x + w / 2 < screenW / 2 ? 0 : maxX;
        if (floatParams.x == target) return;
        final int from = floatParams.x;
        edgeAnimating = true;
        ValueAnimator anim = ValueAnimator.ofInt(from, target);
        anim.setDuration(170);
        anim.addUpdateListener(a -> {
            if (floatParams == null || wm == null || floatView == null) return;
            floatParams.x = (int) a.getAnimatedValue();
            try {
                wm.updateViewLayout(floatView, floatParams);
            } catch (IllegalArgumentException ignored) {}
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                edgeAnimating = false;
                if (floatParams != null) floatParams.x = target;
            }
        });
        anim.start();
    }

    private void savePosition(Activity activity) {
        if (!memoryEnabled || floatParams == null) return;
        int w = viewW();
        int maxX = Math.max(0, screenW - w);
        int side = floatParams.x + w / 2 < screenW / 2 ? 0 : 1;
        float ratio = screenH > 0 ? (float) floatParams.y / (float) screenH : 0f;
        getPosPrefs(activity).edit()
            .putInt("float_side", side)
            .putFloat("float_y_ratio", ratio)
            .putInt("float_w", screenW)
            .putInt("float_h", screenH)
            .putInt("float_x", floatParams.x)
            .putInt("float_y", floatParams.y)
            .apply();
    }

    private void applySavedPosition(Activity activity) {
        SharedPreferences p = getPosPrefs(activity);
        int w = floatView == null ? 0 : floatView.getMeasuredWidth();
        int maxX = Math.max(0, screenW - w);
        float ratio = p.getFloat("float_y_ratio", -1f);
        if (ratio < 0f) {
            int legacyY = p.getInt("float_y", dp2px(activity, 100));
            int savedX = p.getInt("float_x", screenW - dp2px(activity, 100));
            floatParams.x = Math.max(0, Math.min(savedX, maxX));
            floatParams.y = Math.max(0, Math.min(legacyY, screenH - dp2px(activity, 60)));
            return;
        }
        int side = p.getInt("float_side", 1);
        floatParams.x = side == 0 ? 0 : maxX;
        int y = (int) (ratio * screenH);
        floatParams.y = Math.max(0, Math.min(y, screenH - dp2px(activity, 40)));
    }

    private void openMainActivity() {
        Activity activity = activityRef.get();
        if (activity == null) return;
        try {
            Intent i = new Intent(activity, com.xinji.heartbeat.MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(i);
        } catch (Exception ignored) {}
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

    private boolean getPrefBool(String key, boolean def) {
        Activity activity = activityRef.get();
        if (activity != null) {
            return activity.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(key, def);
        }
        return def;
    }

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
