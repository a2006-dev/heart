package com.xinji.heartbeat;
import android.animation.*;
import androidx.fragment.app.FragmentActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;
import java.util.ArrayList;
import java.util.List;
public class OnboardingActivity extends FragmentActivity {
    private ViewPager2 viewPager;
    private LinearLayout dotsLayout;
    private Button btnNext;
    private TextView btnSkip;
    private int currentPage = 0;
    private static final int PERM_REQ = 999;
    private final PageData[] pages = {
        new PageData("❤️", "心迹", "一款精致的心率监测工具\n连接蓝牙心率设备，实时查看心跳"),
        new PageData("📡", "电脑联动", "同一WiFi下推送到电脑\n浏览器/OBS实时显示心率悬浮窗\n支持透明背景，适合直播"),
        new PageData("📱", "悬浮窗", "在其他应用上层显示心率\n5种样式可切换\n拖拽、锁屏、自定义颜色"),
        new PageData("🎮", "游戏模式", "打游戏时自动记录心率变化\n生成折线图分析\n通知栏可一键停止"),
        new PageData("📍\uFE0F", "位置权限", "蓝牙扫描需要定位权限\n发现附近的心率设备", "location"),
        new PageData("🖥️", "悬浮窗权限", "心率悬浮窗显示在其他应用上层", "overlay"),
        new PageData("🔔", "通知权限", "设备断开时发送通知提醒\n后台心率监测", "notification"),
        new PageData("🎮", "使用统计权限", "游戏模式检测当前应用", "usage"),
        new PageData("🚀", "准备就绪！", "开始您的心率监测之旅", "done")
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        if (prefs.getBoolean("onboarding_done", false)) {
            startSplashThenMain();
            return;
        }
        setContentView(R.layout.activity_onboarding);
        viewPager = findViewById(R.id.viewPager);
        dotsLayout = findViewById(R.id.dotsLayout);
        btnNext = findViewById(R.id.btnNext);
        btnSkip = findViewById(R.id.btnSkip);
        viewPager.setAdapter(new OnboardingAdapter());
        setupDots(0);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int pos) {
                currentPage = pos;
                setupDots(pos);
                updateButton(pos);
                if (pos > 3 && pos < pages.length - 1) {
                    requestPagePermission(pos);
                }
            }
        });
        btnNext.setOnClickListener(v -> {
            int last = pages.length - 1;
            if (currentPage < last) {
                viewPager.setCurrentItem(currentPage + 1, true);
            } else {
                goToSplash();
            }
        });
        btnSkip.setOnClickListener(v -> goToSplash());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        }
    }
    private void updateButton(int pos) {
        if (pos == pages.length - 1) {
            btnNext.setText("✨ 开始体验");
        } else {
            btnNext.setText("下一步");
        }
        btnSkip.setVisibility(pos > 2 ? View.GONE : View.VISIBLE);
    }
    private void setupDots(int current) {
        dotsLayout.removeAllViews();
        for (int i = 0; i < pages.length; i++) {
            View dot = new View(this);
            int size = dp(8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dp(5), 0, dp(5), 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(android.R.drawable.presence_offline);
            dot.setAlpha(i == current ? 1f : 0.2f);
            if (i == current) {
                ObjectAnimator.ofFloat(dot, "scaleX", 0.8f, 1.3f).setDuration(400).start();
                ObjectAnimator.ofFloat(dot, "scaleY", 0.8f, 1.3f).setDuration(400).start();
            }
            dotsLayout.addView(dot);
        }
    }
    private void requestPagePermission(int pos) {
        String type = pages[pos].permType;
        if (type == null) return;
        switch (type) {
            case "location":
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, PERM_REQ);
                break;
            case "notification":
                if (Build.VERSION.SDK_INT >= 33) {
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
                        ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, PERM_REQ);
                }
                break;
            case "overlay":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this))
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
                break;
            case "usage":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                    if (usm != null) {
                        long now = System.currentTimeMillis();
                        if (usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 1000, now).isEmpty())
                            startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    }
                }
                break;
        }
    }
    private void goToSplash() {
        getSharedPreferences("app_settings", MODE_PRIVATE)
            .edit().putBoolean("onboarding_done", true).apply();
        startSplashThenMain();
    }
    private void startSplashThenMain() {
        startActivity(new Intent(this, SplashActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
    public PageData getPageData(int pos) {
        if (pos >= 0 && pos < pages.length) return pages[pos];
        return null;
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    static class PageData {
        String icon, title, desc, permType;
        PageData(String icon, String title, String desc) { this.icon=icon; this.title=title; this.desc=desc; }
        PageData(String icon, String title, String desc, String permType) { this(icon,title,desc); this.permType=permType; }
    }
    class OnboardingAdapter extends androidx.viewpager2.adapter.FragmentStateAdapter {
        OnboardingAdapter() { super(OnboardingActivity.this); }
        @Override public int getItemCount() { return pages.length; }
        @Override
        public androidx.fragment.app.Fragment createFragment(int pos) {
            return OnboardingPageFragment.newInstance(pos);
        }
    }
}
