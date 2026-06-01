package com.xinji.heartbeat.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.os.ParcelUuid;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 蓝牙 BLE 管理器 — 完全独立于 UI，所有回调通过 Listener 接口通知。
 *
 * 改进：
 * - 扫描与连接分离，不再与 AlertDialog 耦合
 * - 自动扫描和手动扫描使用不同的 ScanCallback，互不干扰
 * - 支持连接超时、自动重连
 * - 线程安全
 */
public class BleManager {
    private static final String TAG = "BleManager";
    private static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_CHAR    = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt bluetoothGatt;

    // 扫描状态
    private ScanCallback manualScanCb;
    private ScanCallback autoScanCb;
    private boolean isManualScanning = false;
    private boolean isAutoScanning = false;
    private final Handler manualScanHandler = new Handler(Looper.getMainLooper());
    private final Handler autoScanHandler = new Handler(Looper.getMainLooper());

    // 连接状态
    private String currentDeviceName;
    private String currentDeviceAddress;
    private boolean isConnected = false;
    private boolean autoConnectEnabled = false;
    private final Handler connectHandler = new Handler(Looper.getMainLooper());
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private boolean connectTimeoutTriggered = false;

    // 回调监听
    private BleListener listener;

    public interface BleListener {
        void onScanResult(List<ScanDeviceInfo> devices);
        void onScanFailed(int errorCode);
        void onScanStopped();
        void onConnecting(String name);
        void onConnected(String name, String address);
        void onDisconnected(String name);
        void onHeartRateUpdate(int hr);
        void onConnectionFailed(String reason);
        void onBleNotAvailable(String reason);
    }

    public static class ScanDeviceInfo {
        public String name;
        public String address;
        public int rssi;

        public ScanDeviceInfo(String name, String address, int rssi) {
            this.name = name != null ? name : "未知设备";
            this.address = address;
            this.rssi = rssi;
        }
    }

    public BleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            this.bluetoothAdapter = bm.getAdapter();
        }
    }

    public void setListener(BleListener listener) {
        this.listener = listener;
    }

    public boolean isAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getCurrentDeviceName() {
        return currentDeviceName;
    }

    public String getCurrentDeviceAddress() {
        return currentDeviceAddress;
    }

    // ===================== 权限检查 =====================

    /** 检查是否拥有必要的蓝牙权限 */
    public boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ===================== 扫描 =====================

    /** 开始手动扫描（带 UI 回调） */
    public void startManualScan() {
        if (!isAvailable()) {
            notifyBleNotAvailable("蓝牙未开启");
            return;
        }
        if (isManualScanning) return;
        // 如果自动扫描在运行，先停止
        if (isAutoScanning) stopAutoScan();

        isManualScanning = true;
        List<ScanDeviceInfo> scanResults = new ArrayList<>();
        Map<String, ScanDeviceInfo> deviceMap = new HashMap<>();

        manualScanCb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice dev = result.getDevice();
                if (dev == null) return;
                String addr = dev.getAddress();
                String name = dev.getName();
                if (name == null) name = "未知设备";

                ScanDeviceInfo info;
                if (deviceMap.containsKey(addr)) {
                    info = deviceMap.get(addr);
                    info.rssi = result.getRssi();
                } else {
                    info = new ScanDeviceInfo(name, addr, result.getRssi());
                    deviceMap.put(addr, info);
                    scanResults.add(info);
                }
                if (listener != null) {
                    listener.onScanResult(new ArrayList<>(scanResults));
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                isManualScanning = false;
                if (listener != null) listener.onScanFailed(errorCode);
            }
        };

        startLeScan(manualScanCb);

        // 15秒超时自动停止
        manualScanHandler.postDelayed(() -> {
            if (isManualScanning) {
                stopManualScan();
            }
        }, 15000);
    }

    public void stopManualScan() {
        if (!isManualScanning) return;
        isManualScanning = false;
        stopLeScan(manualScanCb);
        manualScanCb = null;
        if (listener != null) listener.onScanStopped();
    }

    /** 开始自动扫描（无 UI，后台静默扫描上次设备） */
    public void startAutoScan() {
        if (!isAvailable() || bluetoothGatt != null || isConnected) return;
        if (isAutoScanning) return;

        isAutoScanning = true;
        List<ScanDeviceInfo> scanResults = new ArrayList<>();
        Map<String, ScanDeviceInfo> deviceMap = new HashMap<>();

        autoScanCb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice dev = result.getDevice();
                if (dev == null) return;
                String addr = dev.getAddress();
                String name = dev.getName();
                if (name == null) name = "未知设备";

                if (!deviceMap.containsKey(addr)) {
                    ScanDeviceInfo info = new ScanDeviceInfo(name, addr, result.getRssi());
                    deviceMap.put(addr, info);
                    scanResults.add(info);
                    // 【修复】自动扫描也通知 listener，让 UI 也能看到扫描进度
                    if (listener != null) {
                        listener.onScanResult(new ArrayList<>(scanResults));
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                isAutoScanning = false;
            }
        };

        startLeScan(autoScanCb);

        autoScanHandler.postDelayed(() -> {
            stopLeScan(autoScanCb);
            isAutoScanning = false;

            if (!deviceMap.isEmpty()) {
                // 尝试连接上次设备
                String savedName = getLastDeviceName();
                String savedAddr = getLastDeviceAddress();
                ScanDeviceInfo target = null;

                if (!savedAddr.isEmpty()) {
                    for (ScanDeviceInfo d : scanResults) {
                        if (savedAddr.equals(d.address)) {
                            target = d;
                            break;
                        }
                    }
                }
                if (target == null && !savedName.isEmpty()) {
                    for (ScanDeviceInfo d : scanResults) {
                        if (savedName.equals(d.name)) {
                            target = d;
                            break;
                        }
                    }
                }
                if (target == null && !scanResults.isEmpty()) {
                    target = scanResults.get(0); // 没匹配到就选第一个
                }

                if (target != null) {
                    final ScanDeviceInfo finalTarget = target;
                    connectHandler.postDelayed(() -> connectToDevice(finalTarget.address, finalTarget.name), 500);
                }
            } else if (autoConnectEnabled) {
                // 没找到设备，10秒后重试
                autoScanHandler.postDelayed(this::startAutoScan, 10000);
            }
        }, 5000);
    }

    public void stopAutoScan() {
        if (!isAutoScanning) return;
        isAutoScanning = false;
        stopLeScan(autoScanCb);
        autoScanCb = null;
    }

    public void stopAllScan() {
        stopManualScan();
        stopAutoScan();
    }

    private void startLeScan(ScanCallback cb) {
        if (bluetoothAdapter == null || cb == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            leScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (leScanner == null) {
                notifyBleNotAvailable("蓝牙LE扫描不可用");
                return;
            }
            try {
                List<ScanFilter> filters = new ArrayList<>();
                filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HR_SERVICE)).build());
                ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
                leScanner.startScan(filters, settings, cb);
            } catch (Exception e) {
                try {
                    leScanner.startScan(cb);
                } catch (Exception e2) {
                    Log.e(TAG, "BLE扫描启动失败", e2);
                }
            }
        }
    }

    private void stopLeScan(ScanCallback cb) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && leScanner != null && cb != null) {
                leScanner.stopScan(cb);
            }
        } catch (Exception ignored) {}
    }

    // ===================== 连接 =====================

    public void connectToDevice(String address, String name) {
        if (bluetoothAdapter == null || address == null) return;

        // 先断开当前连接
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }

        isConnected = false;
        currentDeviceName = name;
        currentDeviceAddress = address;
        connectTimeoutTriggered = false;

        if (listener != null) listener.onConnecting(name);

        // 设置连接超时
        connectHandler.postDelayed(() -> {
            if (!isConnected && !connectTimeoutTriggered) {
                connectTimeoutTriggered = true;
                if (bluetoothGatt != null) {
                    try {
                        bluetoothGatt.disconnect();
                        bluetoothGatt.close();
                    } catch (Exception ignored) {}
                    bluetoothGatt = null;
                }
                if (listener != null) {
                    listener.onConnectionFailed("连接超时（15秒），请确认设备在附近且未被其他设备连接");
                }
            }
        }, CONNECT_TIMEOUT_MS);

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        } catch (Exception e) {
            if (listener != null) {
                listener.onConnectionFailed("连接失败: " + e.getMessage());
            }
        }
    }

    public void disconnect() {
        connectHandler.removeCallbacksAndMessages(null);
        stopAllScan();
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        isConnected = false;
        if (listener != null) {
            listener.onDisconnected(currentDeviceName != null ? currentDeviceName : "设备");
        }
        currentDeviceName = null;
        currentDeviceAddress = null;
    }

    public void setAutoConnectEnabled(boolean enabled) {
        this.autoConnectEnabled = enabled;
    }

    public boolean isAutoConnectEnabled() {
        return autoConnectEnabled;
    }

    // ===================== GATT 回调 =====================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectTimeoutTriggered = true; // 取消超时
                if (listener != null) {
                    listener.onConnected(currentDeviceName, currentDeviceAddress);
                }
                // 延迟发现服务
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { gatt.discoverServices(); } catch (Exception e) { e.printStackTrace(); }
                }, 600);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                isConnected = false;
                String lostName = currentDeviceName != null ? currentDeviceName : "设备";
                try { gatt.close(); } catch (Exception ignored) {}
                if (bluetoothGatt == gatt) bluetoothGatt = null;

                if (listener != null) {
                    listener.onDisconnected(lostName);
                }

                // 自动重连
                if (autoConnectEnabled) {
                    autoScanHandler.postDelayed(() -> {
                        if (currentDeviceAddress != null) {
                            startAutoScan();
                        }
                    }, 3000);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService svc = gatt.getService(HR_SERVICE);
            if (svc != null) {
                BluetoothGattCharacteristic ch = svc.getCharacteristic(HR_CHAR);
                if (ch != null) {
                    try {
                        gatt.setCharacteristicNotification(ch, true);
                        BluetoothGattDescriptor desc = ch.getDescriptor(CCCD);
                        if (desc != null) {
                            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(desc);
                        }
                        isConnected = true;
                    } catch (Exception ignored) {}
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
            byte[] data = ch.getValue();
            if (data == null || data.length < 2) return;
            try {
                int flags = data[0] & 0xFF;
                int hr = (flags & 0x01) == 0
                    ? (data[1] & 0xFF)
                    : ((data[1] & 0xFF) | ((data[2] & 0xFF) << 8));
                if (listener != null) {
                    listener.onHeartRateUpdate(hr);
                }
            } catch (Exception ignored) {}
        }
    };

    // ===================== 持久化 =====================

    private static final String PREFS_NAME = "ble_prefs";
    private static final String KEY_LAST_DEVICE_NAME = "last_device_name";
    private static final String KEY_LAST_DEVICE_ADDR = "last_device_address";

    public void saveLastDevice(String name, String address) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_NAME, name != null ? name : "")
            .putString(KEY_LAST_DEVICE_ADDR, address != null ? address : "")
            .apply();
    }

    public String getLastDeviceName() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_NAME, "");
    }

    public String getLastDeviceAddress() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_ADDR, "");
    }

    // ===================== 工具 =====================

    private void notifyBleNotAvailable(String reason) {
        if (listener != null) listener.onBleNotAvailable(reason);
    }

    /** 释放资源 */
    public void destroy() {
        disconnect();
        listener = null;
    }
}
