package com.xinji.heartbeat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.xinji.heartbeat.bluetooth.BleManager;
import com.xinji.heartbeat.core.HeartRateManager;
import com.xinji.heartbeat.server.BroadcastServer;
import com.xinji.heartbeat.widget.FloatWindowManager;

import java.util.List;

/**
 * 心迹主界面 — 重构版，只做 UI 协调，具体逻辑委托给子模块。
 *
 * 改进：
 * - BleManager 分离：蓝牙扫描/连接/重连独立管理
 * - BroadcastServer 分离：端口 fallback + 线程池 + 心跳检测
 * - FloatWindowManager 分离：所有悬浮窗操作独立
 * - MainActivity 从 37237 字节瘦身到约 600 行
 */
public class MainActivity extends FragmentActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNav;
    private Handler mainHandler;

    // 管理器
    private BleManager bleManager;
    private FloatWindowManager floatWindowManager;
    private BroadcastServer broadcastServer;
    private HeartRateManager heartRateManager;

    // 设置
    SharedPreferences prefs;
    boolean autoConnectEnabled = false;
    boolean floatMemoryEnabled = true;
    boolean broadcastEnabled = false;

    // Fragments
    HomeFragment homeFragment;
    ConnectFragment connectFragment;
    SettingsFragment settingsFragment;

    // ==================== 生命周期 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());

        // 初始化设置
        prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        autoConnectEnabled = prefs.getBoolean("auto_connect", false);
        floatMemoryEnabled = prefs.getBoolean("float_memory", true);
        boolean floatLocked = prefs.getBoolean("float_locked", false);
        broadcastEnabled = prefs.getBoolean("broadcast_enabled", false);

        // 初始化心率管理器
        heartRateManager = HeartRateManager.getInstance(this);

        // 初始化蓝牙管理器
        bleManager = new BleManager(this);
        bleManager.setAutoConnectEnabled(autoConnectEnabled);
        bleManager.setListener(bleListener);

        // 初始化悬浮窗管理器
        floatWindowManager = new FloatWindowManager(this);
        floatWindowManager.setMemoryEnabled(floatMemoryEnabled);
        floatWindowManager.setLocked(floatLocked);

        // 初始化广播服务器
        broadcastServer = new BroadcastServer(this);
        broadcastServer.setListener(broadcastListener);

        // 初始化 UI
        setFullScreen();
        setupBottomNav();

        // 注册广播接收器
        registerBroadcastReceivers();

        // 创建通知渠道
        createNotificationChannel();

        // 请求通知权限（Android 13+）
        requestNotificationPermissionIfNeeded();

        // 延迟启动
        mainHandler.postDelayed(() -> {
            // 启动前台保活服务
            try {
                startService(new Intent(this, com.xinji.heartbeat.core.HeartRateService.class));
            } catch (Exception ignored) {}
            
            if (broadcastEnabled) startBroadcastServer();
            boolean shouldShowFloat = floatMemoryEnabled && prefs.getBoolean("float_visible", false);
            if (shouldShowFloat) floatWindowManager.show();
            if (autoConnectEnabled) bleManager.startAutoScan();
        }, 500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 【修复】GAME_STATE 通过 BroadcastReceiver 处理，不再在这里处理
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (floatWindowManager.handlePermissionResult(requestCode, resultCode)) {
            return;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(broadcastControlReceiver); } catch (Exception ignored) {}
        if (broadcastServer != null && broadcastServer.isRunning()) broadcastServer.stop();
        if (bleManager != null) bleManager.destroy();
    }

    // ==================== UI ====================

    private void setFullScreen() {
        Window w = getWindow();
        View d = w.getDecorView();
        w.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            d.post(() -> {
                try {
                    w.setDecorFitsSystemWindows(false);
                    w.getInsetsController().hide(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    w.getInsetsController().setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } catch (Exception ignored) {}
            });
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            } catch (Exception ignored) {}
        }
    }

    private void setupBottomNav() {
        viewPager = findViewById(R.id.viewPager);
        bottomNav = findViewById(R.id.bottomNav);

        viewPager.setOffscreenPageLimit(3);
        viewPager.setAdapter(new ViewPagerAdapter(this));
        viewPager.setUserInputEnabled(false);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) viewPager.setCurrentItem(0, false);
            else if (id == R.id.nav_connect) viewPager.setCurrentItem(1, false);
            else if (id == R.id.nav_game) viewPager.setCurrentItem(2, false);
            else if (id == R.id.nav_settings) viewPager.setCurrentItem(3, false);
            return true;
        });
    }

    void switchToTab(int tabIndex) {
        viewPager.setCurrentItem(tabIndex, true);
    }

    // ==================== Fragment Adapter ====================

    class ViewPagerAdapter extends FragmentStateAdapter {
        public ViewPagerAdapter(@NonNull FragmentActivity fa) { super(fa); }

        @NonNull @Override
        public Fragment createFragment(int pos) {
            switch (pos) {
                case 0: return homeFragment = new HomeFragment();
                case 1: return connectFragment = new ConnectFragment();
                case 2: return new GameModeFragment();
                default: return settingsFragment = new SettingsFragment();
            }
        }

        @Override public int getItemCount() { return 4; }
    }

    // ==================== Broadcast 接收 ====================

    private final BroadcastReceiver broadcastControlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.xinji.heartbeat.START_BROADCAST".equals(action)) {
                startBroadcastServer();
            } else if ("com.xinji.heartbeat.STOP_BROADCAST".equals(action)) {
                stopBroadcastServer();
            } else if ("com.xinji.heartbeat.GAME_STATE".equals(action)) {
                boolean inGame = intent.getBooleanExtra("in_game", false);
                setGameModeMiniFloat(inGame);
            }
        }
    };

    private void registerBroadcastReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.xinji.heartbeat.START_BROADCAST");
        filter.addAction("com.xinji.heartbeat.STOP_BROADCAST");
        filter.addAction("com.xinji.heartbeat.GAME_STATE");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(broadcastControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(broadcastControlReceiver, filter);
        }
    }

        // ==================== 蓝牙回调 ====================

    private final BleManager.BleListener bleListener = new BleManager.BleListener() {
        @Override
        public void onScanResult(List<BleManager.ScanDeviceInfo> devices) {
            if (connectFragment != null) {
                connectFragment.onScanResult(devices);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                    "蓝牙扫描失败（错误码:" + errorCode + "），请重启蓝牙", Toast.LENGTH_SHORT).show();
                if (connectFragment != null)
                    connectFragment.appendLogExternal("❌ 蓝牙扫描失败（错误码:" + errorCode + "）", 0xFFff5d7c);
            });
        }

        @Override
        public void onScanStopped() {
            if (connectFragment != null) connectFragment.onScanStopped();
        }

        @Override
        public void onConnecting(String name) {
            runOnUiThread(() -> {
                if (connectFragment != null) {
                    connectFragment.updateStatus(name, "❤️ 连接中...");
                    connectFragment.appendLogExternal("🔗 正在连接: " + name, 0xFFcbadbb);
                }
                Toast.makeText(MainActivity.this, "连接 " + name, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onConnected(String name, String address) {
            bleManager.saveLastDevice(name, address);
            runOnUiThread(() -> {
                if (connectFragment != null) {
                    connectFragment.updateStatus(name, "已连接");
                    connectFragment.appendLogExternal("✅ 已成功连接: " + name + " (" + address + ")", 0xFF4CAF50);
                }
                if (homeFragment != null) homeFragment.updateDevice(name);
                if (!floatWindowManager.isVisible()) floatWindowManager.show();
                if (broadcastServer != null) {
                    broadcastServer.updateDevice(name);
                    broadcastServer.setConnected(true);
                }
            });
        }

        @Override
        public void onDisconnected(String name) {
            runOnUiThread(() -> {
                if (connectFragment != null) {
                    connectFragment.updateStatus("⚡ 未连接", "离线");
                    connectFragment.appendLogExternal("🔌 已断开: " + name, 0xFFffaa33);
                }
                if (homeFragment != null) homeFragment.updateDevice("⚡ 未连接");
                if (floatWindowManager.isVisible()) floatWindowManager.setOffline();
                if (broadcastServer != null) broadcastServer.setConnected(false);
                showDisconnectNotification(name);
            });
        }

        @Override
        public void onHeartRateUpdate(int hr) {
            runOnUiThread(() -> {
                if (homeFragment != null) homeFragment.updateHR(hr);
                floatWindowManager.updateHR(hr);
                if (broadcastServer != null && broadcastEnabled) broadcastServer.updateHR(hr);
            });
            heartRateManager.notifyListeners(hr);
        }

        @Override
        public void onConnectionFailed(String reason) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
                if (connectFragment != null)
                    connectFragment.appendLogExternal("❌ " + reason, 0xFFff5d7c);
            });
        }

        @Override
        public void onBleNotAvailable(String reason) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show();
                if (connectFragment != null)
                    connectFragment.appendLogExternal("⚠️ " + reason, 0xFFffaa33);
            });
        }
    };

    // ==================== 广播服务器回调 ====================

    private final BroadcastServer.ServerListener broadcastListener = new BroadcastServer.ServerListener() {
        @Override
        public void onServerStarted(String ip, int port) {
            broadcastEnabled = true;
            prefs.edit().putBoolean("broadcast_enabled", true).apply();
            runOnUiThread(() -> {
                if (settingsFragment != null) settingsFragment.updateBroadcastStatus();
                Toast.makeText(MainActivity.this, "广播服务已启动: " + ip + ":" + port, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onServerStopped() {
            broadcastEnabled = false;
            prefs.edit().putBoolean("broadcast_enabled", false).apply();
            runOnUiThread(() -> {
                if (settingsFragment != null) settingsFragment.updateBroadcastStatus();
            });
        }

        @Override
        public void onError(String message) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            });
        }
    };

    // ==================== 对外接口 ====================

    public BleManager getBleManager() { return bleManager; }
    public FloatWindowManager getFloatWindowManager() { return floatWindowManager; }
    public BroadcastServer getBroadcastServer() { return broadcastServer; }
    public Handler getMainHandler() { return mainHandler; }

    public void startBroadcastServer() {
        if (!broadcastServer.canStartBroadcast()) {
            Toast.makeText(this, "请先连接 WiFi 或 USB 网络", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!broadcastServer.isRunning()) {
            broadcastServer.start();
            if (bleManager.isConnected()) {
                broadcastServer.updateDevice(bleManager.getCurrentDeviceName());
                broadcastServer.updateHR(heartRateManager.getCurrentHR());
                broadcastServer.setConnected(true);
            }
        }
    }

    public void stopBroadcastServer() {
        if (broadcastServer != null && broadcastServer.isRunning()) {
            broadcastServer.stop();
        }
    }

    public void disconnectDevice() {
        bleManager.disconnect();
        floatWindowManager.hide();
        if (connectFragment != null) connectFragment.updateStatus("⚡ 未连接", "待机");
        if (homeFragment != null) homeFragment.updateDevice("⚡ 未连接");
    }

    public void setGameModeMiniFloat(boolean inGame) {
        // 游戏模式进入/退出时切换悬浮窗样式
        // 进入游戏 → 切到超小迷你样式（样式4），不遮挡游戏画面
        // 退出游戏 → 恢复用户设置的样式
        if (inGame) {
            // 【修复】保存当前样式时标记版本，防止退出时覆盖用户中途修改
            int currentStyle = prefs.getInt("float_style", 0);
            if (currentStyle != 4) { // 不是迷你样式才保存，避免重复进入覆盖已保存值
                prefs.edit().putInt("float_style_before_game", currentStyle).apply();
            }
            prefs.edit().putInt("float_style", 4).apply();
        } else {
            // 【修复】检查游戏过程中用户是否手动改过样式
            int currentStyle = prefs.getInt("float_style", 4);
            int beforeGame = prefs.getInt("float_style_before_game", 0);
            // 只有当前仍是迷你样式(4)才恢复；如果用户中途改过，尊重用户选择
            if (currentStyle == 4) {
                prefs.edit().putInt("float_style", beforeGame).apply();
            }
        }
        recreateFloatWindow();
    }

    public void recreateFloatWindow() {
        floatWindowManager.recreate();
    }

    public void startGameModeService() {
        try {
            startService(new Intent(this, com.xinji.heartbeat.core.GameModeService.class));
        } catch (Exception ignored) {}
    }

    // ==================== 通知 ====================

    private static final String NOTIF_CHANNEL_ID = "heart_disconnect";
    private static final int NOTIF_DISCONNECT_ID = 100;

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIF_CHANNEL_ID, "设备断开", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("手表端断开连接时通知");
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            }
        }
    }

    private void showDisconnectNotification(String deviceName) {
        try {
            String name = deviceName != null ? deviceName : "设备";
            Intent tapIntent = new Intent(this, MainActivity.class);
            tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification notif = new Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("设备已断开")
                .setContentText(name + " 已断开连接")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_DISCONNECT_ID, notif);
        } catch (Exception ignored) {}
    }
}