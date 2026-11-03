package com.xinji.heartbeat.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HeartRateDetector {

    private static final String TAG = "HeartRateDetector";
    private static final long DETECT_TIMEOUT_MS = 15000;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private int retryCount = 0;
    private static final int MAX_RETRIES = 1;

    public interface DetectCallback {
        void onConnecting();
        void onDataReceived(String serviceUuid, String charUuid, byte[] data);
        void onResult(List<DetectResult> results);
        void onError(String reason);
    }

    private final Context context;
    private final String deviceAddress;
    private final String deviceName;
    private final DetectCallback callback;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean finished = false;
    private final List<BluetoothGattCharacteristic> pendingNotifyChars = new ArrayList<>();
    private int pendingNotifyIndex = 0;
    private boolean descriptorWritePending = false;

    private final Map<String, List<byte[]>> samplesByChar = new HashMap<>();
    private final Map<String, String> charToService = new HashMap<>();

    public HeartRateDetector(Context context, String deviceAddress, String deviceName, DetectCallback callback) {
        this.context = context.getApplicationContext();
        this.deviceAddress = deviceAddress;
        this.deviceName = deviceName;
        this.callback = callback;
    }

    public synchronized void start() {
        if (finished) return;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) { fail("蓝牙服务不可用"); return; }
        bluetoothAdapter = bm.getAdapter();
        if (bluetoothAdapter == null) { fail("蓝牙适配器不可用"); return; }

        try {
            if (bluetoothGatt != null) {
                try { bluetoothGatt.disconnect(); bluetoothGatt.close(); } catch (Exception ignored) {}
                bluetoothGatt = null;
            }
        } catch (Exception ignored) {}

        if (callback != null) callback.onConnecting();

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                bluetoothGatt = device.connectGatt(context, false, gattCallback);
            }
        } catch (Exception e) {
            fail("连接失败: " + e.getMessage());
            return;
        }

        handler.postDelayed(this::onTimeout, DETECT_TIMEOUT_MS);
    }

    public void stop() {
        finishWithResults();
    }

    private void onTimeout() {
        if (finished) return;
        Log.w(TAG, "检测超时");
        boolean hasAnyData = false;
        for (List<byte[]> list : samplesByChar.values()) {
            if (list != null && !list.isEmpty()) { hasAnyData = true; break; }
        }
        if (hasAnyData) {
            finishWithResults();
        } else if (retryCount < MAX_RETRIES) {
            retryCount++;
            Log.d(TAG, "未收到数据，第 " + (retryCount + 1) + " 次重试连接...");
            if (callback != null) callback.onError("未收到数据，重试中 (" + retryCount + "/" + (MAX_RETRIES + 1) + ")");
            disconnect();
            handler.postDelayed(this::start, 1500);
        } else {
            fail("检测超时，未收到数据");
        }
    }

    private synchronized void finishWithResults() {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        disconnect();

        List<DetectResult> results = new ArrayList<>();
        for (Map.Entry<String, List<byte[]>> entry : samplesByChar.entrySet()) {
            String charUuid = entry.getKey();
            List<byte[]> samples = entry.getValue();
            String svcUuid = charToService.getOrDefault(charUuid, "");
            DetectResult result = HeartRateJudger.judge(samples, svcUuid, charUuid);
            results.add(result);
        }

        if (callback != null) callback.onResult(results);
    }

    private synchronized void fail(String reason) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        disconnect();
        if (callback != null) callback.onError(reason);
    }

    private void disconnect() {
        try {
            if (bluetoothGatt != null) {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        } catch (Exception ignored) {}
    }

    private void subscribeNext(BluetoothGatt gatt) {
        if (finished || gatt != bluetoothGatt || descriptorWritePending) return;
        while (pendingNotifyIndex < pendingNotifyChars.size()) {
            BluetoothGattCharacteristic ch = pendingNotifyChars.get(pendingNotifyIndex);
            try {
                if (!gatt.setCharacteristicNotification(ch, true)) {
                    pendingNotifyIndex++;
                    continue;
                }
                BluetoothGattDescriptor desc = ch.getDescriptor(CCCD);
                if (desc == null) {
                    pendingNotifyIndex++;
                    continue;
                }
                int props = ch.getProperties();
                desc.setValue((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                descriptorWritePending = true;
                if (!gatt.writeDescriptor(desc)) {
                    descriptorWritePending = false;
                    pendingNotifyIndex++;
                    continue;
                }
                return;
            } catch (Exception e) {
                descriptorWritePending = false;
                pendingNotifyIndex++;
            }
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (!finished) fail("GATT连接失败，状态码 " + status);
                return;
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "已连接: " + deviceName);
                handler.postDelayed(() -> {
                    try { gatt.discoverServices(); } catch (Exception e) {
                        fail("服务发现失败");
                    }
                }, 500);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "已断开: " + deviceName);
                finishWithResults();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (finished || gatt != bluetoothGatt) return;
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("服务发现失败，状态码 " + status); return; }
            List<BluetoothGattService> services = gatt.getServices();

            pendingNotifyChars.clear();
            pendingNotifyIndex = 0;
            descriptorWritePending = false;
            for (BluetoothGattService svc : services) {
                String svcUuid = svc.getUuid().toString().toLowerCase();
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    int props = ch.getProperties();
                    if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                            && (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) continue;
                    String chUuid = ch.getUuid().toString().toLowerCase();
                    charToService.put(chUuid, svcUuid);
                    samplesByChar.computeIfAbsent(chUuid, k -> new ArrayList<byte[]>());
                    pendingNotifyChars.add(ch);
                }
            }
            int subscribed = pendingNotifyChars.size();
            subscribeNext(gatt);
            Log.d(TAG, "准备订阅 " + subscribed + " 个 Notify/Indicate 特征");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (finished || gatt != bluetoothGatt || descriptor == null || !CCCD.equals(descriptor.getUuid())) return;
            descriptorWritePending = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD 写入失败，继续下一个特征: " + status);
            }
            pendingNotifyIndex++;
            handler.postDelayed(() -> subscribeNext(gatt), 80);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
            byte[] data = ch.getValue();
            if (data == null) return;
            String chUuid = ch.getUuid().toString().toLowerCase();
            String svcUuid = ch.getService().getUuid().toString().toLowerCase();

            List<byte[]> samples = samplesByChar.get(chUuid);
            if (samples == null) {
                samples = new ArrayList<>();
                samplesByChar.put(chUuid, samples);
                charToService.put(chUuid, svcUuid);
            }
            samples.add(data.clone());

            if (callback != null) {
                callback.onDataReceived(svcUuid, chUuid, data);
            }
        }
    };
}
