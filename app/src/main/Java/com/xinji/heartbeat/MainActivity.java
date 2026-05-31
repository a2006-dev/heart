package com.xinji.heartbeat;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.util.*;

/**
 * 心迹主界面 — 蓝牙心率监测 & 游戏模式。
 * BLE 扫描/连接模块参考了 https://github.com/milirstudio/xinxiu（心宿 · 米粒工作室）
 */
public class MainActivity extends FragmentActivity {
    private ViewPager2 viewPager;
    private BottomNavigationView bottomNav;
    BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private final UUID HR_CHAR = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private WindowManager wm;
    private View floatView;
    private TextView floatHrView;
    private WindowManager.LayoutParams floatParams;
    boolean floatVisible = false, floatLocked = false;
    private int screenW, screenH;
    String currentDevice = null;
    private View.OnTouchListener dragListener;
    private int dragInitX, dragInitY;
    private float dragTouchX, dragTouchY;
    SharedPreferences prefs;
    boolean autoConnectEnabled = false;
    boolean floatMemoryEnabled = true;
    boolean shouldShowFloatOnStart = false;
    private boolean isAutoScanning = false;
    Handler scanHandler;
    private boolean isManualScanning = false;
    private BluetoothLeScanner leScanner;
    private ScanCallback currentScanCb;
    HomeFragment homeFragment;
    ConnectFragment connectFragment;
    SettingsFragment settingsFragment;
    HeartRateBroadcastServer broadcastServer;
    boolean broadcastEnabled = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        autoConnectEnabled = prefs.getBoolean("auto_connect", false);
        floatMemoryEnabled = prefs.getBoolean("float_memory", true);
        floatLocked = prefs.getBoolean("float_locked", false);
        shouldShowFloatOnStart = floatMemoryEnabled && prefs.getBoolean("float_visible", false);
        floatVisible = false;
        broadcastEnabled = prefs.getBoolean("broadcast_enabled", false);
        scanHandler = new Handler(Looper.getMainLooper());
        initBluetooth();
        getScreenSize();
        setFullScreen();
        setupBottomNav();
        startBackgroundService();
        scanHandler.postDelayed(() -> {
            if (broadcastEnabled) startBroadcastServer();
            if (shouldShowFloatOnStart) showFloatWindow();
            if (autoConnectEnabled) startAutoScan();
        }, 500);
        handleGameStateIntent(getIntent());
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
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleGameStateIntent(intent);
    }
    private void handleGameStateIntent(Intent intent) {
        if (intent != null && "com.xinji.heartbeat.GAME_STATE".equals(intent.getAction())) {
            boolean inGame = intent.getBooleanExtra("in_game", false);
            setGameModeMiniFloat(inGame);
        }
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
                        android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
                    w.getInsetsController().setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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
    class ViewPagerAdapter extends FragmentStateAdapter {
        public ViewPagerAdapter(@NonNull FragmentActivity fa) { super(fa); }
        @NonNull @Override
        public Fragment createFragment(int pos) {
            Fragment f;
            switch (pos) {
                case 0: f = new HomeFragment(); homeFragment = (HomeFragment) f; break;
                case 1: f = new ConnectFragment(); connectFragment = (ConnectFragment) f; break;
                case 2: f = new GameModeFragment(); break;
                default: f = new SettingsFragment(); settingsFragment = (SettingsFragment) f; break;
            }
            return f;
        }
        @Override public int getItemCount() { return 4; }
    }
    void switchToTab(int tabIndex) {
        viewPager.setCurrentItem(tabIndex, true);
    }
    private void initBluetooth() {
        BluetoothManager m = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (m != null) bluetoothAdapter = m.getAdapter();
        checkPermissions();
    }
    private void getScreenSize() {
        try { Point s = new Point(); getWindowManager().getDefaultDisplay().getSize(s); screenW = s.x; screenH = s.y; }
        catch (Exception e) { screenW = 1080; screenH = 1920; }
    }
    private void checkPermissions() {
        List<String> pList = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pList.add(android.Manifest.permission.BLUETOOTH_SCAN);
            pList.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            pList.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pList.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            pList.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        String[] p = pList.toArray(new String[0]);
        if (!allGranted(p)) {
            requestPermissions(p, 100);
            Toast.makeText(this, "部分权限未授予，蓝牙连接等功能可能受限", Toast.LENGTH_LONG).show();
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            boolean someDenied = false;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    someDenied = true;
                    break;
                }
            }
            if (someDenied) {
                Toast.makeText(this, "部分权限被拒绝，蓝牙连接功能可能无法使用", Toast.LENGTH_LONG).show();
            }
        }
    }
    private boolean allGranted(String[] p) {
        for (String s : p) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private void startBackgroundService() {
        try {
            Intent i = new Intent(this, GameModeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
        } catch (Exception ignored) {}
    }
    void startScanWithDialog() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) { Toast.makeText(this, "请开启蓝牙", Toast.LENGTH_SHORT).show(); return; }
        if (isAutoScanning) {
            try { if (leScanner != null && currentScanCb != null) leScanner.stopScan(currentScanCb); } catch (Exception ignored) {}
            isAutoScanning = false;
        }
        isManualScanning = true;
        List<String> names = new ArrayList<>();
        Map<String, BluetoothDevice> map = new HashMap<>();
        Map<String, Integer> rssi = new HashMap<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                String n = names.get(pos);
                int r = rssi.getOrDefault(n, -100);
                int bars = r >= -55 ? 4 : r >= -70 ? 3 : r >= -85 ? 2 : r >= -100 ? 1 : 0;
                tv.setText(" 📶 " + bars + "/4  " + n);
                tv.setTextColor(0xFFFFD0D8); tv.setTextSize(16);
                tv.setPadding(dp2px(12), dp2px(8), dp2px(12), dp2px(8));
                return v;
            }
        };
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择心率设备");
        b.setAdapter(adapter, (d, i) -> { BluetoothDevice dev = map.get(names.get(i)); if (dev != null) { stopScan(); connect(dev); } d.dismiss(); });
        b.setNegativeButton("取消", (d, i) -> { stopScan(); d.dismiss(); });
        AlertDialog dlg = b.create(); dlg.show();
        Window win = dlg.getWindow();
        if (win != null) { win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); win.setLayout(dp2px(280), WindowManager.LayoutParams.WRAP_CONTENT); }
        startScan(names, map, rssi, adapter);
        scanHandler.postDelayed(() -> { stopScan(); isManualScanning = false; if (dlg.isShowing()) { Toast.makeText(this, "扫描结束", Toast.LENGTH_SHORT).show(); dlg.setTitle("选择心率设备 (扫描结束)"); } }, 15000);
    }
    private void startScan(List<String> names, Map<String, BluetoothDevice> map, Map<String, Integer> rssi, ArrayAdapter<String> adapter) {
        if (bluetoothAdapter == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            leScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (leScanner == null) {
                runOnUiThread(() -> Toast.makeText(this, "蓝牙LE扫描不可用，请检查蓝牙是否开启", Toast.LENGTH_SHORT).show());
                return;
            }
            currentScanCb = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    BluetoothDevice dev = result.getDevice(); if (dev == null) return;
                    String n = dev.getName();
                    if (n != null && !map.containsKey(n)) { map.put(n, dev); names.add(n); rssi.put(n, result.getRssi()); runOnUiThread(adapter::notifyDataSetChanged); }
                    else if (n != null) { rssi.put(n, result.getRssi()); runOnUiThread(adapter::notifyDataSetChanged); }
                }
                @Override public void onScanFailed(int errorCode) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "蓝牙扫描失败（错误码:" + errorCode + "），请重启蓝牙", Toast.LENGTH_SHORT).show());
                }
            };
            try {
                List<ScanFilter> filters = new ArrayList<>();
                try {
                    filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HR_SERVICE)).build());
                } catch (Exception ignored) {}
                ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
                leScanner.startScan(filters, settings, currentScanCb);
            } catch (Exception e) {
                try {
                    leScanner.startScan(currentScanCb);
                } catch (Exception e2) {
                    Log.e("BLE", "蓝牙扫描完全失败", e2);
                }
            }
        }
    }
    void stopScan() {
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && leScanner != null && currentScanCb != null) leScanner.stopScan(currentScanCb); } catch (Exception ignored) {}
        currentScanCb = null;
        // 只清除自动扫描相关的回调和消息，不影响手动扫描的超时
        if (!isManualScanning) {
            scanHandler.removeCallbacksAndMessages(null);
        }
    }
    void stopAutoScanOnly() {
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && leScanner != null && currentScanCb != null) leScanner.stopScan(currentScanCb); } catch (Exception ignored) {}
        currentScanCb = null;
        // 仅停止自动扫描，不清除手动扫描的定时任务
    }
    void startAutoScan() {
        if (!autoConnectEnabled || bluetoothAdapter == null || !bluetoothAdapter.isEnabled() || bluetoothGatt != null || isAutoScanning) return;
        if (currentDevice != null && bluetoothGatt != null) return;
        isAutoScanning = true;
        List<String> names = new ArrayList<>();
        Map<String, BluetoothDevice> map = new HashMap<>();
        Map<String, Integer> rssi = new HashMap<>();
        ArrayAdapter<String> dummy = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        startScan(names, map, rssi, dummy);
        scanHandler.postDelayed(() -> {
            stopScan(); isAutoScanning = false;
            if (!map.isEmpty()) {
                Map<String, BluetoothDevice> copyMap;
                synchronized (map) {
                    copyMap = new HashMap<>(map);
                }
                String saved = prefs.getString("last_device", "");
                BluetoothDevice target = null;
                if (!saved.isEmpty()) {
                    for (BluetoothDevice d : copyMap.values()) {
                        if (saved.equals(d.getName())) { target = d; break; }
                    }
                }
                if (target == null) target = copyMap.values().iterator().next();
                final BluetoothDevice finalTarget = target;
                scanHandler.postDelayed(() -> connect(finalTarget), 500);
            } else if (autoConnectEnabled) {
                scanHandler.postDelayed(this::startAutoScan, 10000);
            }
        }, 5000);
    }
    private void connect(BluetoothDevice dev) {
        if (dev == null) return;
        currentDevice = dev.getName();
        prefs.edit().putString("last_device", currentDevice).apply();
        runOnUiThread(() -> {
            Toast.makeText(this, "连接 " + currentDevice, Toast.LENGTH_SHORT).show();
            if (connectFragment != null) connectFragment.updateStatus(currentDevice, "已连接");
            if (homeFragment != null) homeFragment.updateDevice(currentDevice);
            if (!floatVisible) showFloatWindow();
            if (broadcastServer != null) {
                broadcastServer.updateDevice(currentDevice);
                broadcastServer.setConnected(true);
            }
        });
        try { bluetoothGatt = dev.connectGatt(this, false, gattCallback); } catch (Exception e) { e.printStackTrace(); }
    }
    void startGameModeService() {
        try {
            startService(new Intent(this, GameModeService.class));
        } catch (Exception ignored) {}
    }
    void startBroadcastServer() {
        if (broadcastServer == null) {
            broadcastServer = new HeartRateBroadcastServer(8080, this);
        }
        if (!broadcastServer.isRunning()) {
            broadcastServer.start();
        }
        broadcastEnabled = true;
        prefs.edit().putBoolean("broadcast_enabled", true).apply();
    }
    void stopBroadcastServer() {
        if (broadcastServer != null && broadcastServer.isRunning()) {
            broadcastServer.stop();
        }
        broadcastEnabled = false;
        prefs.edit().putBoolean("broadcast_enabled", false).apply();
    }
    void disconnectDevice() {
        if (bluetoothGatt != null) { try { bluetoothGatt.disconnect(); } catch (Exception ignored) {} }
        if (floatVisible) hideFloatWindow();
        if (connectFragment != null) connectFragment.updateStatus("⚡ 未连接", "待机");
        if (homeFragment != null) homeFragment.updateDevice("⚡ 未连接");
        currentDevice = null;
        if (broadcastServer != null) broadcastServer.setConnected(false);
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show();
    }
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                runOnUiThread(() -> { if (connectFragment != null) connectFragment.updateStatus(currentDevice, "❤️ 同步中"); });
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { gatt.discoverServices(); } catch (Exception e) { e.printStackTrace(); }
                }, 600);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                String lostDevice = currentDevice != null ? currentDevice : "设备";
                try { gatt.close(); } catch (Exception ignored) {}
                if (bluetoothGatt == gatt) bluetoothGatt = null;
                runOnUiThread(() -> {
                    if (connectFragment != null) connectFragment.updateStatus("⚡ 未连接", "离线");
                    if (homeFragment != null) homeFragment.updateDevice("⚡ 未连接");
                    if (floatVisible) hideFloatWindow();
                    currentDevice = null;
                    if (autoConnectEnabled) scanHandler.postDelayed(MainActivity.this::startAutoScan, 3000);
                    // 弹出断开通知
                    showDisconnectNotification(lostDevice);
                });
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService svc = gatt.getService(HR_SERVICE);
            if (svc != null) {
                BluetoothGattCharacteristic ch = svc.getCharacteristic(HR_CHAR);
                if (ch != null) {
                    try {
                        gatt.setCharacteristicNotification(ch, true);
                        BluetoothGattDescriptor desc = ch.getDescriptor(CCCD);
                        if (desc != null) { desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); gatt.writeDescriptor(desc); }
                    } catch (Exception ignored) {}
                }
            }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
            byte[] data = ch.getValue(); if (data == null || data.length < 2) return;
            try {
                int flags = data[0] & 0xFF;
                int hr = (flags & 0x01) == 0 ? (data[1] & 0xFF) : ((data[1] & 0xFF) | ((data[2] & 0xFF) << 8));
                runOnUiThread(() -> { if (homeFragment != null) homeFragment.updateHR(hr); updateFloatHR(hr); });
                if (broadcastServer != null && broadcastEnabled) broadcastServer.updateHR(hr);
                HeartRateManager.getInstance(MainActivity.this).notifyListeners(hr);
            } catch (Exception ignored) {}
        }
    };
    void showFloatWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 101);
            } catch (Exception e) {
                Toast.makeText(this, "请在设置中手动开启「悬浮窗」权限", Toast.LENGTH_LONG).show();
                try {
                    Intent detailIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(detailIntent);
                } catch (Exception ignored) {}
            }
            return;
        }
        if (!floatVisible) {
            createFloatView();
            if (floatView == null) {
                floatVisible = false;
                if (floatMemoryEnabled) prefs.edit().putBoolean("float_visible", false).apply();
                return;
            }
            floatVisible = true;
            if (floatMemoryEnabled) prefs.edit().putBoolean("float_visible", true).apply();
        }
    }
    void hideFloatWindow() {
        if (floatVisible && floatView != null && wm != null) {
            try { wm.removeView(floatView); } catch (Exception ignored) {}
        }
        floatVisible = false;
    }
    void hideFloatWindowByUser() {
        hideFloatWindow();
        if (floatMemoryEnabled) prefs.edit().putBoolean("float_visible", false).apply();
    }
    void updateFloatTouchable() {
        if (floatView == null || floatParams == null || wm == null) return;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (floatLocked) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        floatParams.flags = flags;
        try { wm.updateViewLayout(floatView, floatParams); } catch (Exception ignored) {}
    }
    private void createFloatView() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        int style = prefs.getInt("float_style", 0);
        floatHrView = null;
        floatView = buildFloatViewByStyle(style);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (floatLocked) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        floatParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        floatParams.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences posPrefs = getSharedPreferences("float_pos", MODE_PRIVATE);
        floatParams.x = posPrefs.getInt("float_x", screenW - dp2px(100));
        floatParams.y = posPrefs.getInt("float_y", dp2px(100));
        dragListener = (v, event) -> {
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
                    if (floatMemoryEnabled) {
                        getSharedPreferences("float_pos", MODE_PRIVATE).edit()
                                .putInt("float_x", floatParams.x).putInt("float_y", floatParams.y).apply();
                    }
                    return true;
            }
            return false;
        };
        floatView.setOnTouchListener(dragListener);
        try {
            wm.addView(floatView, floatParams);
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "悬浮窗开启失败，请检查权限设置", Toast.LENGTH_LONG).show());
            floatView = null;
        }
    }
    private View buildFloatViewByStyle(int style) {
        switch (style) {
            case 1: return buildPillStyle();
            case 2: return buildCircleStyle();
            case 3: return buildHeartStyle();
            case 4: return buildMiniStyle();
            default: return buildTextStyle();
        }
    }
    private View buildTextStyle() {
        TextView v = new TextView(this);
        v.setText("-- BPM");
        v.setTextColor(0xFFFF5D7C);
        v.setTextSize(20);
        v.setTypeface(null, Typeface.BOLD);
        v.setShadowLayer(3, 1, 1, 0xAA000000);
        v.setPadding(dp2px(8), dp2px(4), dp2px(8), dp2px(4));
        return v;
    }
    private View buildPillStyle() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp2px(14), dp2px(8), dp2px(14), dp2px(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xDD0A0E17);
        bg.setCornerRadius(dp2px(20));
        bg.setStroke(dp2px(2), 0x8800D4FF);
        v.setBackground(bg);
        v.setElevation(dp2px(6));
        TextView pulse = new TextView(this);
        pulse.setText("●");
        pulse.setTextColor(0xFF00D4FF);
        pulse.setTextSize(10);
        LinearLayout.LayoutParams pulseLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pulseLp.rightMargin = dp2px(8);
        v.addView(pulse, pulseLp);
        TextView hr = new TextView(this);
        hr.setText("--");
        hr.setTextColor(0xFFFFFFFF);
        hr.setTextSize(20);
        hr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.addView(hr);
        TextView unit = new TextView(this);
        unit.setText(" bpm");
        unit.setTextColor(0x8800D4FF);
        unit.setTextSize(11);
        unit.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        unitLp.leftMargin = dp2px(4);
        unitLp.bottomMargin = dp2px(2);
        v.addView(unit, unitLp);
        floatHrView = hr;
        return v;
    }
    private View buildCircleStyle() {
        TextView v = new TextView(this);
        v.setText("--");
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(16);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        int size = dp2px(56);
        v.setWidth(size);
        v.setHeight(size);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xDDFF5D7C);
        bg.setStroke(dp2px(2), 0xFFFFFFFF);
        v.setBackground(bg);
        return v;
    }
    private View buildHeartStyle() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xDD0A0E17);
        bg.setCornerRadius(dp2px(12));
        bg.setStroke(dp2px(1), 0x66FF4466);
        v.setBackground(bg);
        v.setElevation(dp2px(4));
        final TextView pulseIcon = new TextView(this);
        pulseIcon.setText("❤️");
        pulseIcon.setTextSize(18);
        pulseIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconLp.bottomMargin = dp2px(4);
        v.addView(pulseIcon, iconLp);
        TextView hr = new TextView(this);
        hr.setText("--");
        hr.setTextColor(0xFFFF5577);
        hr.setTextSize(22);
        hr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hr.setGravity(Gravity.CENTER);
        hr.setMinWidth(dp2px(40));  // ← 保证两位数(如99)完整显示
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        v.addView(hr, hrLp);
        TextView label = new TextView(this);
        label.setText("脉搏");
        label.setTextColor(0xAAFF5577);
        label.setTextSize(9);
        label.setTypeface(null, Typeface.BOLD);
        v.addView(label);
        floatHrView = hr;
        return v;
    }
    private View buildMiniStyle() {
        TextView v = new TextView(this);
        v.setText("--");
        v.setTextColor(0xFFFF5D7C);
        v.setTextSize(10);
        v.setTypeface(null, Typeface.BOLD);
        v.setShadowLayer(1, 1, 1, 0xAA000000);
        v.setPadding(dp2px(3), dp2px(1), dp2px(3), dp2px(1));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x880A0E17);
        bg.setCornerRadius(dp2px(6));
        v.setBackground(bg);
        floatHrView = v;
        return v;
    }
    void recreateFloatWindow() {
        if (floatVisible) {
            hideFloatWindow();
            showFloatWindow();
        }
    }
    private boolean gameModeMiniFloat = false;
    private int savedFloatStyleBeforeGame = 0;
    void setGameModeMiniFloat(boolean enable) {
        if (enable == gameModeMiniFloat) return;
        gameModeMiniFloat = enable;
        if (enable) {
            savedFloatStyleBeforeGame = prefs.getInt("float_style", 0);
            prefs.edit().putInt("float_style", 4).apply();
            if (floatVisible) {
                hideFloatWindow();
                showFloatWindow();
            }
        } else {
            prefs.edit().putInt("float_style", savedFloatStyleBeforeGame).apply();
            if (floatVisible || gameModeMiniFloat) {
                hideFloatWindow();
                scanHandler.postDelayed(() -> {
                    showFloatWindow();
                }, 200);
            }
        }
    }
    private void updateFloatHR(int hr) {
        if (floatView != null && floatVisible) runOnUiThread(() -> {
            int style = prefs.getInt("float_style", 0);
            if (style == 0) {
                ((TextView) floatView).setText(hr + " BPM");
            } else if (style == 1 || style == 3 || style == 4) {
                if (floatHrView != null) floatHrView.setText(String.valueOf(hr));
                if (style == 3) {
                    try {
                        View pulseIcon = ((LinearLayout) floatView).getChildAt(0);
                        if (pulseIcon instanceof TextView) {
                            pulseIcon.setScaleX(1.4f);
                            pulseIcon.setScaleY(1.4f);
                            pulseIcon.postDelayed(() -> {
                                pulseIcon.setScaleX(1.0f);
                                pulseIcon.setScaleY(1.0f);
                            }, 150);
                        }
                    } catch (Exception ignored) {}
                }
            } else if (style == 2) {
                ((TextView) floatView).setText(String.valueOf(hr));
            }
        });
    }
    void showDisconnectNotification(String deviceName) {
        try {
            String CHANNEL_ID_DISCONNECT = "disconnect_alert";
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID_DISCONNECT, "断开提醒", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("蓝牙设备断开时提醒");
                if (nm != null) nm.createNotificationChannel(channel);
            }
            Notification notification = new Notification.Builder(this, CHANNEL_ID_DISCONNECT)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("📡 设备已断开")
                    .setContentText(deviceName + " 已断开连接")
                    .setAutoCancel(true)
                    .build();
            if (nm != null) nm.notify(100, notification);
        } catch (Exception ignored) {}
    }
    private int dp2px(int dp) { return (int) (dp * getResources().getDisplayMetrics().density); }
    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 101) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                scanHandler.postDelayed(this::showFloatWindow, 200);
            } else {
                if (settingsFragment != null) settingsFragment.syncFloatSwitch(false);
            }
        }
    }
    @Override
    protected void onDestroy() {
        try {
            if (broadcastServer != null) { broadcastServer.stop(); }
            if (bluetoothGatt != null) { bluetoothGatt.close(); bluetoothGatt = null; }
            if (floatVisible && floatView != null && wm != null) { try { wm.removeView(floatView); } catch (Exception ignored) {} }
            stopScan();
            try { unregisterReceiver(broadcastControlReceiver); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
