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


public class OnboardingActivity extends FragmentActivity {
    private ViewPager2 viewPager;
    private LinearLayout dotsLayout;
    private Button btnNext;
    private TextView btnSkip;
    private int currentPage = 0;
    private static final int PERM_REQ = 999;

    private final PageData[] pages = {
        new PageData("❤️", "心迹", "连接蓝牙心率设备\n实时查看每一次心跳"),
        new PageData("📡", "多端同步", "WiFi/USB/MQTT 推送到电脑\n支持 OBS 直播叠加"),
        new PageData("🌐", "网络状态", "读取当前 WiFi / 移动网络状态\n用于广播地址检测与网络诊断", "permission"),
        new PageData("📍", "位置权限", "扫描发现附近的心率设备", "permission"),
        new PageData("🖥️", "悬浮窗权限", "心率悬浮窗显示在其他应用上层", "permission"),
        new PageData("🔔", "通知权限", "设备断开时发送通知提醒", "permission"),
        new PageData("🔋", "电池优化", "忽略电池优化，保持后台运行", "permission"),
        new PageData("🚀", "准备就绪", "开始您的心率监测之旅", "done")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        if (prefs.getBoolean("onboarding_done", false)) {
            goToMain();
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
            }
        });

        btnNext.setOnClickListener(v -> {
            int last = pages.length - 1;
            if (currentPage < last) {
                viewPager.setCurrentItem(currentPage + 1, true);
            } else {
                finishOnboarding();
            }
        });
        btnSkip.setOnClickListener(v -> finishOnboarding());

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
        btnSkip.setVisibility(pos >= pages.length - 2 ? View.GONE : View.VISIBLE);
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


    public void requestNetworkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> need = new java.util.ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_NETWORK_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.ACCESS_NETWORK_STATE);
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_WIFI_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.ACCESS_WIFI_STATE);
            }
            if (!need.isEmpty()) {
                ActivityCompat.requestPermissions(this, need.toArray(new String[0]), PERM_REQ);
                return;
            }
        }
        android.widget.Toast.makeText(this, "网络状态权限已授予", android.widget.Toast.LENGTH_SHORT).show();
    }


    public boolean hasNetworkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_NETWORK_STATE)
                    == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_WIFI_STATE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }


    public void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            ActivityCompat.requestPermissions(this, new String[]{
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            }, PERM_REQ);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            ActivityCompat.requestPermissions(this, new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION
            }, PERM_REQ);
        }
    }


    public void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName())));
        }
    }


    public void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS
                }, PERM_REQ);
            }
        }
    }


    public void requestBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception ignored) {}
        }
    }


    public boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void finishOnboarding() {
        getSharedPreferences("app_settings", MODE_PRIVATE)
            .edit().putBoolean("onboarding_done", true).apply();


        requestNetworkPermissions();
        requestBluetoothPermissions();
        requestOverlayPermission();
        requestNotificationPermission();


        startActivity(new Intent(this, SplashActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void goToMain() {
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
        String icon, title, desc, type;
        PageData(String icon, String title, String desc) { this.icon=icon; this.title=title; this.desc=desc; }
        PageData(String icon, String title, String desc, String type) { this(icon,title,desc); this.type=type; }
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
