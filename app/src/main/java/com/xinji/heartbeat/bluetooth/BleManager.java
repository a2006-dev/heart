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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class BleManager {
    private static final String TAG = "BleManager";
    private static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_CHAR    = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt bluetoothGatt;

    private ScanCallback manualScanCb;
    private ScanCallback autoScanCb;
    private boolean isManualScanning = false;
    private boolean isAutoScanning = false;
    private final Handler manualScanHandler = new Handler(Looper.getMainLooper());
    private final Handler autoScanHandler = new Handler(Looper.getMainLooper());

    private String currentDeviceName;
    private String currentDeviceAddress;
    private boolean isConnected = false;
    private boolean autoConnectEnabled = false;
    private final Handler connectHandler = new Handler(Looper.getMainLooper());
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private boolean connectTimeoutTriggered = false;

    private BleListener listener;

    private boolean broadScan = false;

    public void setBroadScan(boolean broad) {
        this.broadScan = broad;
    }

    public boolean isBroadScan() {
        return broadScan;
    }

    public boolean isManualScanning() {
        return isManualScanning;
    }

    public interface BleListener {
        void onScanResult(List<ScanDeviceInfo> devices);
        void onScanFailed(int errorCode);
        void onScanStopped();
        void onConnecting(String name);
        void onConnected(String name, String address);
        void onDisconnected(String name);
        /** @deprecated 使用 onHeartRateUpdate(int hr, int[] rrIntervals) */
        @Deprecated
        void onHeartRateUpdate(int hr);
        void onHeartRateUpdate(int hr, int[] rrIntervals);
        void onConnectionFailed(String reason);
        void onBleNotAvailable(String reason);
    }

    public interface FilterCallback {
        void onServiceDiscovered(String serviceUuid, String charUuid);
        void onHeartRateCharFound(String serviceUuid, String charUuid);
        void onSubscribed(String serviceUuid, String charUuid);
        void onFilterComplete(int totalServices, int totalChars, int matched);
        void onError(String msg);
        void onHeartRateData(int hr);
    }

    public static class ScanDeviceInfo {
        public String name;
        public String address;
        public int rssi;
        public boolean isKnown = false; // 是否已连接过（由 BleManager 在扫描时标记）

        public List<String> serviceUuids = new ArrayList<>();

        public ScanDeviceInfo(String name, String address, int rssi) {
            this.name = name != null ? name : "未知设备";
            this.address = address;
            this.rssi = rssi;
        }

        public ScanDeviceInfo(String name, String address, int rssi, List<String> serviceUuids) {
            this.name = name != null ? name : "未知设备";
            this.address = address;
            this.rssi = rssi;
            if (serviceUuids != null) this.serviceUuids = serviceUuids;
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

    public void startManualScan() {
        if (!isAvailable()) {
            notifyBleNotAvailable("蓝牙未开启");
            return;
        }
        if (isManualScanning) return;

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

                List<String> svcUuids = new ArrayList<>();
                if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                    for (ParcelUuid pu : result.getScanRecord().getServiceUuids()) {
                        svcUuids.add(pu.getUuid().toString().toLowerCase());
                    }
                }

                String savedAddr = getLastDeviceAddress();
                String savedName = getLastDeviceName();

                ScanDeviceInfo info;
                if (deviceMap.containsKey(addr)) {
                    info = deviceMap.get(addr);
                    info.rssi = result.getRssi();

                    for (String u : svcUuids) {
                        if (!info.serviceUuids.contains(u)) info.serviceUuids.add(u);
                    }
                } else {
                    info = new ScanDeviceInfo(name, addr, result.getRssi(), svcUuids);
                    // 标记已知设备（已连接过的优先展示）
                    info.isKnown = addr.equals(savedAddr) || name.equals(savedName);
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
                String savedName = getLastDeviceName();
                String savedAddr = getLastDeviceAddress();
                ScanDeviceInfo target = null;

                // 只连接上次连过的设备
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
                // 不再自动连接未知设备（避免连到别人的手表）

                if (target != null) {
                    final ScanDeviceInfo finalTarget = target;
                    connectHandler.postDelayed(() -> connectToDevice(finalTarget.address, finalTarget.name), 500);
                }
            } else if (autoConnectEnabled) {
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
                ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
                if (broadScan) {
                    leScanner.startScan(null, settings, cb);
                } else {
                    List<ScanFilter> filters = new ArrayList<>();
                    filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HR_SERVICE)).build());
                    leScanner.startScan(filters, settings, cb);
                }
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

    public void connectToDevice(String address, String name) {
        if (bluetoothAdapter == null || address == null) return;

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

    private FilterCallback filterCallback;
    private boolean filterMode = false;
    private int filterTotalServices = 0;
    private int filterTotalChars = 0;
    private int filterMatched = 0;
    private String filterDeviceAddress;
    private String filterDeviceName;
    private String matchedServiceUuid;
    private String matchedCharUuid;
    private static final String[] HEART_KEYWORDS = {"heart", "hr", "rate", "心率", "pulse", "bpm"};

    public void connectAndFilterServices(String address, String name, FilterCallback callback) {
        if (bluetoothAdapter == null || address == null) {
            if (callback != null) callback.onError("蓝牙适配器不可用");
            return;
        }

        this.filterDeviceAddress = address;
        this.filterDeviceName = name;
        this.filterCallback = callback;
        this.filterMode = true;
        this.filterTotalServices = 0;
        this.filterTotalChars = 0;
        this.filterMatched = 0;

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

        connectHandler.postDelayed(() -> {
            if (!isConnected && !connectTimeoutTriggered) {
                connectTimeoutTriggered = true;
                if (bluetoothGatt != null) {
                    try { bluetoothGatt.disconnect(); bluetoothGatt.close(); } catch (Exception ignored) {}
                    bluetoothGatt = null;
                }
                filterMode = false;
                if (filterCallback != null) filterCallback.onError("连接超时（15秒）");
            }
        }, CONNECT_TIMEOUT_MS);

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            bluetoothGatt = device.connectGatt(context, false, filterGattCallback);
        } catch (Exception e) {
            filterMode = false;
            if (filterCallback != null) filterCallback.onError("连接失败: " + e.getMessage());
        }
    }

    private boolean matchesHeartRate(String uuidStr, String charName) {
        String lower = uuidStr.toLowerCase(Locale.ROOT);
        for (String kw : HEART_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        if (charName != null) {
            String lowerName = charName.toLowerCase(Locale.ROOT);
            for (String kw : HEART_KEYWORDS) {
                if (lowerName.contains(kw)) return true;
            }
        }
        return false;
    }

    private final BluetoothGattCallback filterGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectTimeoutTriggered = true;
                if (listener != null) listener.onConnected(currentDeviceName, currentDeviceAddress);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { gatt.discoverServices(); } catch (Exception e) {
                        if (filterCallback != null) filterCallback.onError("服务发现失败");
                    }
                }, 600);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                isConnected = false;
                filterMode = false;
                try { gatt.close(); } catch (Exception ignored) {}
                if (bluetoothGatt == gatt) bluetoothGatt = null;
                if (listener != null) listener.onDisconnected(currentDeviceName != null ? currentDeviceName : "设备");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            List<BluetoothGattService> services = gatt.getServices();
            filterTotalServices = services.size();

            for (BluetoothGattService svc : services) {
                String svcUuid = svc.getUuid().toString().toLowerCase(Locale.ROOT);
                List<BluetoothGattCharacteristic> chars = svc.getCharacteristics();
                for (BluetoothGattCharacteristic ch : chars) {
                    filterTotalChars++;
                    String chUuid = ch.getUuid().toString().toLowerCase(Locale.ROOT);
                    String chName = guessCharName(chUuid);

                    if (filterCallback != null) {
                        filterCallback.onServiceDiscovered(svcUuid, chUuid + (chName != null ? " (" + chName + ")" : ""));
                    }

                    if (matchesHeartRate(chUuid, chName)) {
                        filterMatched++;
                        matchedServiceUuid = svcUuid;
                        matchedCharUuid = chUuid;
                        if (filterCallback != null) {
                            filterCallback.onHeartRateCharFound(svcUuid, chUuid);
                        }

                        int props = ch.getProperties();
                        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            try {
                                gatt.setCharacteristicNotification(ch, true);
                                BluetoothGattDescriptor desc = ch.getDescriptor(
                                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                                if (desc != null) {
                                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    gatt.writeDescriptor(desc);
                                }
                                isConnected = true;
                                if (filterCallback != null) {
                                    filterCallback.onSubscribed(svcUuid, chUuid);
                                }

                                try {
                                    com.xinji.heartbeat.core.DeviceProfileManager.getInstance(context)
                                        .addOrUpdateProfile(filterDeviceName, filterDeviceAddress, svcUuid, chUuid);
                                } catch (Exception ignored) {}
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (filterMatched == 0) {
                for (BluetoothGattService svc : services) {
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        int props = ch.getProperties();
                        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            try {
                                gatt.setCharacteristicNotification(ch, true);
                                BluetoothGattDescriptor desc = ch.getDescriptor(
                                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                                if (desc != null) {
                                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    gatt.writeDescriptor(desc);
                                }
                                isConnected = true;
                                filterMatched++;
                                if (filterCallback != null) {
                                    filterCallback.onSubscribed(svc.getUuid().toString().toLowerCase(),
                                        ch.getUuid().toString().toLowerCase());
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                if (filterMatched > 0 && filterCallback != null) {
                    filterCallback.onHeartRateCharFound("(fallback)", "已订阅所有 Notify 特征");
                }
            }

            if (filterCallback != null) {
                filterCallback.onFilterComplete(filterTotalServices, filterTotalChars, filterMatched);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
            byte[] data = ch.getValue();
            if (data == null || data.length < 1) return;
            try {
                int hr = -1;

                if (data.length >= 2) {
                    int flags = data[0] & 0xFF;
                    hr = (flags & 0x01) == 0
                        ? (data[1] & 0xFF)
                        : ((data[1] & 0xFF) | ((data[2] & 0xFF) << 8));
                }

                if ((hr <= 0 || hr > 250) && data.length >= 3) {
                    hr = data[2] & 0xFF;
                }
                if ((hr <= 0 || hr > 250) && data.length >= 2) {
                    hr = data[1] & 0xFF;
                }

                if (hr > 20 && hr < 250) {
                    if (filterCallback != null) filterCallback.onHeartRateData(hr);
                    if (listener != null) {
                        // filterGattCallback 也解析 RR
                        int[] rrInts = null;
                        if (data.length >= 3) {
                            int f = data[0] & 0xFF;
                            boolean hasRR = (f & 0x10) != 0;
                            if (hasRR) {
                                int hrBytes = (f & 0x01) == 0 ? 1 : 2;
                                int rrCnt = (data.length - 1 - hrBytes) / 2;
                                if (rrCnt > 0) {
                                    rrInts = new int[rrCnt];
                                    for (int ri = 0; ri < rrCnt; ri++) {
                                        int off = 1 + hrBytes + ri * 2;
                                        if (off + 1 < data.length)
                                            rrInts[ri] = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
                                    }
                                }
                            }
                        }
                        listener.onHeartRateUpdate(hr, rrInts);
                    }
                }
            } catch (Exception ignored) {}
        }
    };

    private String guessCharName(String uuidLower) {
        if (uuidLower.contains("2a37")) return "心率测量";
        if (uuidLower.contains("2a38")) return "身体传感位置";
        if (uuidLower.contains("2a39")) return "心率控制点";
        if (uuidLower.contains("2a5c")) return "心率已存储记录";
        if (uuidLower.contains("2a5d")) return "心率 RR 区间";
        if (uuidLower.contains("2a19")) return "电池电量";
        if (uuidLower.contains("2a24")) return "型号";
        if (uuidLower.contains("2a25")) return "序列号";
        if (uuidLower.contains("2a26")) return "固件版本";
        if (uuidLower.contains("2a27")) return "硬件版本";
        if (uuidLower.contains("2a28")) return "软件版本";
        if (uuidLower.contains("2a29")) return "制造商";
        if (uuidLower.contains("ff06")) return "小米私有数据";
        if (uuidLower.contains("1a02")) return "小米私有数据";
        if (uuidLower.contains("0008") && uuidLower.contains("3512")) return "小米私有心率";
        return null;
    }

    public void setAutoConnectEnabled(boolean enabled) {
        this.autoConnectEnabled = enabled;
    }

    public boolean isAutoConnectEnabled() {
        return autoConnectEnabled;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectTimeoutTriggered = true;
                if (listener != null) {
                    listener.onConnected(currentDeviceName, currentDeviceAddress);
                }

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

                // 解析 RR-Interval（标准心率特征格式）
                int[] rrIntervals = null;
                boolean hasRR = (flags & 0x10) != 0; // Bit4=RR-Interval flag
                if (hasRR) {
                    int hrBytes = (flags & 0x01) == 0 ? 1 : 2;
                    int rrCount = (data.length - 1 - hrBytes) / 2;
                    if (rrCount > 0) {
                        rrIntervals = new int[rrCount];
                        for (int i = 0; i < rrCount; i++) {
                            int offset = 1 + hrBytes + i * 2;
                            if (offset + 1 < data.length) {
                                // RR-Interval 单位: 1/1024 秒
                                rrIntervals[i] = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
                            }
                        }
                    }
                }

                if (listener != null) {
                    listener.onHeartRateUpdate(hr, rrIntervals);
                }
            } catch (Exception ignored) {}
        }
    };

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

    /** 获取已配对设备列表（地址 -> 名称） */
    /** 获取已配对设备列表（地址 -> 名称），只包含 SharedPreferences 中保存的最新设备 */
    public java.util.Map<String, String> getPairedDevices() {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        String name = getLastDeviceName();
        String addr = getLastDeviceAddress();
        if (!name.isEmpty() && !addr.isEmpty()) {
            result.put(addr, name);
        }
        return result;
    }

    /** 清除已保存的设备记录（取消配对/忘记设备） */
    public void clearLastDevice() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_DEVICE_NAME)
            .remove(KEY_LAST_DEVICE_ADDR)
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

    private void notifyBleNotAvailable(String reason) {
        if (listener != null) listener.onBleNotAvailable(reason);
    }

    public void destroy() {
        disconnect();
        listener = null;
    }
}
