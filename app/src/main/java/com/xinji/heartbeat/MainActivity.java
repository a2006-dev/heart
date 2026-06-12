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
import com.xinji.heartbeat.app.HeartEventBus;
import com.xinji.heartbeat.app.HeartServiceLocator;
import com.xinji.heartbeat.app.PreferencesManager;
import com.xinji.heartbeat.bluetooth.BleManager;
import com.xinji.heartbeat.core.HeartRateManager;
import com.xinji.heartbeat.server.BroadcastServer;
import com.xinji.heartbeat.widget.FloatWindowManager;

import java.util.List;

public class MainActivity extends FragmentActivity {
    private ViewPager2 viewPager;
    private BottomNavigationView bottomNav;
    private Handler mainHandler;
    private HeartServiceLocator serviceLocator;
    private PreferencesManager prefs;

    HomeFragment homeFragment;
    ConnectFragment connectFragment;
    SettingsFragment settingsFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());

        serviceLocator = HeartServiceLocator.from(this);
        serviceLocator.setActivity(this);
        prefs = PreferencesManager.from(this);

        boolean autoConnect = prefs.getAutoConnect();
        boolean floatMemory = prefs.getFloatMemory();
        boolean floatVisible = prefs.getFloatVisible();
        boolean broadcastEnabled = prefs.getBroadcastEnabled();
        boolean floatLocked = prefs.getFloatLocked();

        BleManager bleManager = serviceLocator.getBleManager();
        if (bleManager != null) {
            bleManager.setAutoConnectEnabled(autoConnect);
            bleManager.setListener(bleListener);
        }

        BroadcastServer broadcastServer = serviceLocator.getBroadcastServer();
        if (broadcastServer != null) {
            broadcastServer.setListener(broadcastListener);
        }

        setFullScreen();
        setupBottomNav();
        registerBroadcastReceivers();
        createNotificationChannel();
        requestNotificationPermissionIfNeeded();

        mainHandler.postDelayed(() -> {
            try {
                startService(new Intent(this, com.xinji.heartbeat.core.HeartRateService.class));
            } catch (Exception ignored) {}

            if (broadcastEnabled) startBroadcastServer();
            if (floatMemory && floatVisible) {
                FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
                if (fwm != null) fwm.show();
            }
            if (autoConnect && bleManager != null) bleManager.startAutoScan();
        }, 500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
        if (fwm != null) fwm.handlePermissionResult(requestCode, resultCode);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(broadcastControlReceiver); } catch (Exception ignored) {}
        BroadcastServer server = serviceLocator.getBroadcastServer();
        if (server != null) {
            if (server.isRunning()) server.stop();
            server.setListener(null);
        }
        BleManager ble = serviceLocator.getBleManager();
        if (ble != null) {
            ble.setListener(null);
            ble.destroy();
        }
        serviceLocator.setActivity(null);
        serviceLocator = null;
    }

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

    private final BleManager.BleListener bleListener = new BleManager.BleListener() {
        @Override
        public void onScanResult(List<BleManager.ScanDeviceInfo> devices) {
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_SCAN_RESULT, devices);
            if (connectFragment != null) connectFragment.onScanResult(devices);
        }

        @Override
        public void onScanFailed(int errorCode) {
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_SCAN_FAILED, errorCode);
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "蓝牙扫描失败（错误码:" + errorCode + "），请重启蓝牙", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onScanStopped() {
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_SCAN_STOPPED, null);
        }

        @Override
        public void onConnecting(String name) {
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_CONNECTING, name);
        }

        @Override
        public void onConnected(String name, String address) {
            BleManager bleManager = serviceLocator.getBleManager();
            if (bleManager != null) bleManager.saveLastDevice(name, address);
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_CONNECTED, name);
            // 启动5分钟无心率超时检测
            HeartRateManager hrMgr = HeartRateManager.getInstance(MainActivity.this);
            hrMgr.setTimeoutListener(() -> {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "5分钟未收到心率数据，已断开连接", Toast.LENGTH_LONG).show();
                    if (connectFragment != null) {
                        connectFragment.appendLogExternal("⏱ 5分钟未收到心率数据，自动断开", 0xFFffaa33);
                    }
                });
                if (bleManager != null) bleManager.disconnect();
                hrMgr.stopTimeoutCheck();
            });
            hrMgr.startTimeoutCheck();
            runOnUiThread(() -> {
                if (homeFragment != null) homeFragment.updateDevice(name);
                FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
                if (fwm != null && !fwm.isVisible()) fwm.show();
                BroadcastServer server = serviceLocator.getBroadcastServer();
                if (server != null) {
                    server.updateDevice(name);
                    server.setConnected(true);
                }
            });
        }

        @Override
        public void onDisconnected(String name) {
            HeartRateManager.getInstance(MainActivity.this).stopTimeoutCheck();
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_DISCONNECTED, name);
            runOnUiThread(() -> {
                if (homeFragment != null) homeFragment.updateDevice("⚡ 未连接");
                FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
                if (fwm != null && fwm.isVisible()) fwm.setOffline();
                BroadcastServer server = serviceLocator.getBroadcastServer();
                if (server != null) server.setConnected(false);
                showDisconnectNotification(name);
            });
        }

        @Override
        public void onHeartRateUpdate(int hr) {
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_HR_UPDATE, hr);
            runOnUiThread(() -> {
                if (homeFragment != null) homeFragment.updateHR(hr);
                FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
                if (fwm != null) fwm.updateHR(hr);
                BroadcastServer server = serviceLocator.getBroadcastServer();
                if (server != null && prefs.getBroadcastEnabled()) server.updateHR(hr);
            });
        }

        @Override
        public void onConnectionFailed(String reason) {
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_CONNECTION_FAILED, reason);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show());
        }

        @Override
        public void onBleNotAvailable(String reason) {
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_BLE_NOT_AVAILABLE, reason);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show());
        }
    };

    private final BroadcastServer.ServerListener broadcastListener = new BroadcastServer.ServerListener() {
        @Override
        public void onServerStarted(String ip, int port) {
            prefs.setBroadcastEnabled(true);
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_BROADCAST_STARTED, ip + ":" + port);
            runOnUiThread(() -> {
                if (settingsFragment != null) settingsFragment.updateBroadcastStatus();
                Toast.makeText(MainActivity.this, "广播服务已启动: " + ip + ":" + port, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onServerStopped() {
            prefs.setBroadcastEnabled(false);
            HeartEventBus.getInstance().postOnMain(HeartEventBus.EVENT_BROADCAST_STOPPED, null);
            runOnUiThread(() -> {
                if (settingsFragment != null) settingsFragment.updateBroadcastStatus();
            });
        }

        @Override
        public void onError(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
        }
    };

    public void startBroadcastServer() {
        BroadcastServer server = serviceLocator.getBroadcastServer();
        if (server == null || !server.canStartBroadcast()) {
            Toast.makeText(this, "请先连接 WiFi 或 USB 网络", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!server.isRunning()) {
            server.start();
            BleManager ble = serviceLocator.getBleManager();
            if (ble != null && ble.isConnected()) {
                server.updateDevice(ble.getCurrentDeviceName());
                server.updateHR(HeartRateManager.getInstance(this).getCurrentHR());
                server.setConnected(true);
            }
        }
    }

    public void stopBroadcastServer() {
        BroadcastServer server = serviceLocator.getBroadcastServer();
        if (server != null && server.isRunning()) server.stop();
    }

    public void setGameModeMiniFloat(boolean inGame) {
        SharedPreferences sp = prefs.getAppPrefs();
        if (inGame) {
            int currentStyle = sp.getInt("float_style", 0);
            if (currentStyle != 4) {
                sp.edit().putInt("float_style_before_game", currentStyle).apply();
            }
            sp.edit().putInt("float_style", 4).apply();
        } else {
            int currentStyle = sp.getInt("float_style", 4);
            int beforeGame = sp.getInt("float_style_before_game", 0);
            if (currentStyle == 4) {
                sp.edit().putInt("float_style", beforeGame).apply();
            }
        }
        FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
        if (fwm != null) fwm.recreate();
        HeartEventBus.getInstance().post(HeartEventBus.EVENT_FLOAT_STYLE_CHANGED, null);
    }

    public void startGameModeService() {
        try {
            startService(new Intent(this, com.xinji.heartbeat.core.GameModeService.class));
        } catch (Exception ignored) {}
    }

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
