package com.xinji.heartbeat;
import android.Manifest;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.xinji.heartbeat.app.HeartEventBus;
import com.xinji.heartbeat.app.HeartServiceLocator;
import com.xinji.heartbeat.bluetooth.BleManager;
import com.xinji.heartbeat.core.DeviceProfileManager;
import com.xinji.heartbeat.data.model.BleDeviceInfo;
import com.xinji.heartbeat.widget.FloatWindowManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ConnectFragment extends Fragment implements HeartEventBus.EventListener {
    private HeartServiceLocator serviceLocator;
    private List<BleManager.ScanDeviceInfo> deviceList = new ArrayList<>();
    private Button btnScan, btnDisconnect, btnClearLog;
    private TextView tvDevice, tvStatus, tvScanCount, tvLog, tvBroadSearchHint;
    private ScrollView logScrollView;
    private androidx.appcompat.widget.SwitchCompat swBroadSearch;
    private RecyclerView rvDevices;
    private DeviceAdapter deviceAdapter;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private boolean broadSearch = false;
    private static final int MAX_LOG_LINES = 200;
    private final List<LogEntry> logEntries = new ArrayList<>();

    private static class LogEntry {
        long time;
        String text;
        int color;
        LogEntry(long time, String text, int color) {
            this.time = time;
            this.text = text;
            this.color = color;
        }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_connect, container, false);
        serviceLocator = HeartServiceLocator.from(requireContext());
        HeartEventBus.getInstance().register(this);

        tvDevice = v.findViewById(R.id.tvDevice);
        tvStatus = v.findViewById(R.id.tvStatus);
        btnScan = v.findViewById(R.id.btnScan);
        btnDisconnect = v.findViewById(R.id.btnDisconnect);
        tvScanCount = v.findViewById(R.id.tvScanCount);
        swBroadSearch = v.findViewById(R.id.swBroadSearch);
        tvBroadSearchHint = v.findViewById(R.id.tvBroadSearchHint);

        rvDevices = v.findViewById(R.id.rvDevices);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        deviceAdapter = new DeviceAdapter();
        rvDevices.setAdapter(deviceAdapter);

        tvLog = v.findViewById(R.id.tvLog);
        logScrollView = (ScrollView) v.findViewById(R.id.logScrollView);
        btnClearLog = v.findViewById(R.id.btnClearLog);

        btnScan.setOnClickListener(view -> toggleScan());
        btnDisconnect.setOnClickListener(view -> disconnectDevice());
        btnClearLog.setOnClickListener(view -> {
            logEntries.clear();
            updateLogDisplay();
        });

        swBroadSearch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            broadSearch = isChecked;
            deviceAdapter.notifyDataSetChanged();
            appendLog(isChecked ? "🔓 广义搜索模式 — 已显示 ⋮ 可查看各设备广播服务" : "🔒 标准模式 — 仅显示心率设备", 0xFFcbadbb);
            if (isChecked) {
                tvBroadSearchHint.setVisibility(View.VISIBLE);
                tvBroadSearchHint.setText("点击 ⋮ 查看服务UUID，或使用「筛选模式」自动匹配");
            } else {
                tvBroadSearchHint.setVisibility(View.GONE);
            }
            if (isScanning) {
                stopScanInternal();
                handler.postDelayed(() -> {
                    if (!isAdded()) return;
                    BleManager b = serviceLocator.getBleManager();
                    if (b != null && b.isAvailable()) startScan();
                }, 200);
            }
        });

        syncConnectionStatus();
        appendLog("📱 心迹连接页面已加载", 0xFF675c62);
        appendLog("💡 点击「开始搜索」扫描附近设备", 0xFF675c62);
        appendLog("💡 开启「广义搜索」可查看所有设备，点击 ⋮ 选择「筛选模式」自动匹配心率服务", 0xFF675c62);
        return v;
    }

    private void disconnectDevice() {
        BleManager ble = serviceLocator.getBleManager();
        if (ble != null) ble.disconnect();
        try {
            FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
            if (fwm != null) fwm.hide();
        } catch (Exception ignored) {}
        updateStatus("⚡ 未连接", "待机");
        HeartEventBus.getInstance().post(HeartEventBus.EVENT_DISCONNECTED, null);
    }

    private void syncConnectionStatus() {
        BleManager ble = serviceLocator.getBleManager();
        if (ble == null) return;
        if (ble.isConnected()) {
            String name = ble.getCurrentDeviceName();
            updateStatus(name != null ? name : "⚡ 已连接", "已连接");
        } else {
            String savedName = ble.getLastDeviceName();
            if (!savedName.isEmpty() && ble.isAutoConnectEnabled()) {
                updateStatus("⚡ " + savedName, "自动重连中...");
            } else {
                updateStatus("⚡ 未连接", "待机");
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        syncConnectionStatus();
        // 同步扫描状态：如果BleManager在扫描但UI认为没在扫，更新
        BleManager ble = serviceLocator.getBleManager();
        if (ble != null && ble.isManualScanning() && !isScanning) {
            isScanning = true;
            if (btnScan != null) btnScan.setText("⏹ 停止扫描");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopScanInternal();
        handler.removeCallbacksAndMessages(null);
        HeartEventBus.getInstance().unregister(this);
    }

    private void stopScanInternal() {
        BleManager ble = serviceLocator.getBleManager();
        if (ble != null) ble.stopManualScan();
        isScanning = false;
        if (btnScan != null) btnScan.setText("🔍 开始搜索");
    }

    private void appendLog(String text, int color) {
        logEntries.add(new LogEntry(System.currentTimeMillis(), text, color));
        if (logEntries.size() > MAX_LOG_LINES) logEntries.remove(0);
        updateLogDisplay();
    }

    private void updateLogDisplay() {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < logEntries.size(); i++) {
            LogEntry entry = logEntries.get(i);
            String timeStr = sdf.format(new Date(entry.time));
            String line = "[" + timeStr + "] " + entry.text + "\n";
            int start = ssb.length();
            ssb.append(line);
            ssb.setSpan(new ForegroundColorSpan(entry.color), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvLog.setText(ssb);
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void requestBluetoothPermission() {
        new AlertDialog.Builder(requireContext())
            .setTitle("需要蓝牙权限")
            .setMessage("扫描和连接心率设备需要蓝牙权限")
            .setPositiveButton("去设置", (d, w) -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private boolean checkBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermission();
                return false;
            }
        }
        return true;
    }

    private void toggleScan() {
        if (!checkBlePermissions()) return;
        BleManager ble = serviceLocator.getBleManager();
        if (ble == null) return;

        if (isScanning) {
            ble.stopManualScan();
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
            tvScanCount.setText("");
            appendLog("⏹ 扫描已停止", 0xFF675c62);
            return;
        }

        if (!ble.isAvailable()) {
            Toast.makeText(getActivity(), "请开启蓝牙", Toast.LENGTH_SHORT).show();
            appendLog("❌ 蓝牙未开启", 0xFFff5d7c);
            return;
        }

        startScan();
    }

    private void startScan() {
        BleManager ble = serviceLocator.getBleManager();
        if (ble == null) return;

        deviceList.clear();
        deviceAdapter.notifyDataSetChanged();
        updateStatus("⚡ 未连接", "正在扫描...");
        appendLog("🔍 开始扫描" + (broadSearch ? "（广义模式）" : "（心率设备）"), 0xFFcbadbb);

        ble.setBroadScan(broadSearch);
        ble.startManualScan();
        isScanning = true;
        btnScan.setText("⏹ 停止扫描");
        handler.removeCallbacks(scanTimeoutRunnable);
        handler.postDelayed(scanTimeoutRunnable, 15000);
    }

    private final Runnable scanTimeoutRunnable = () -> {
        if (isScanning) {
            BleManager ble = serviceLocator.getBleManager();
            if (ble != null) ble.stopManualScan();
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
            appendLog("⏱ 扫描超时（15秒），已自动停止", 0xFF675c62);
        }
    };

    @Override
    public void onEvent(int type, Object data) {
        switch (type) {
            case HeartEventBus.EVENT_SCAN_RESULT:
                if (data instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<BleManager.ScanDeviceInfo> devices = (List<BleManager.ScanDeviceInfo>) data;
                    handler.post(() -> onScanResult(devices));
                }
                break;
            case HeartEventBus.EVENT_SCAN_STOPPED:
                handler.post(this::onScanStopped);
                break;
            case HeartEventBus.EVENT_CONNECTING:
                if (data instanceof String) {
                    handler.post(() -> {
                        updateStatus((String) data, "❤️ 连接中...");
                        appendLog("🔗 正在连接: " + data, 0xFFcbadbb);
                    });
                }
                break;
            case HeartEventBus.EVENT_CONNECTED:
                if (data instanceof String) {
                    String devName = (String) data;
                    handler.post(() -> updateStatus(devName, "已连接"));
                    // 记录已连接设备到JSON
                    BleManager ble = serviceLocator.getBleManager();
                    if (ble != null) {
                        String addr = ble.getCurrentDeviceAddress();
                        if (addr != null) {
                            DeviceProfileManager mgr = serviceLocator.getDeviceProfileManager();
                            if (mgr != null) mgr.recordDevice(devName, addr);
                        }
                    }
                }
                break;
            case HeartEventBus.EVENT_DISCONNECTED:
                handler.post(() -> {
                    updateStatus("⚡ 未连接", "离线");
                    appendLog("🔌 已断开", 0xFFffaa33);
                });
                break;
            case HeartEventBus.EVENT_CONNECTION_FAILED:
                if (data instanceof String) {
                    handler.post(() -> appendLog("❌ " + data, 0xFFff5d7c));
                }
                break;
            case HeartEventBus.EVENT_BLE_NOT_AVAILABLE:
                if (data instanceof String) {
                    handler.post(() -> appendLog("⚠️ " + data, 0xFFffaa33));
                }
                break;
        }
    }

    class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvDevName, tvDevAddress, tvDevRssi, tvDeviceIcon, btnServiceDetail;
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvDevName = itemView.findViewById(R.id.tvDevName);
                tvDevAddress = itemView.findViewById(R.id.tvDevAddress);
                tvDevRssi = itemView.findViewById(R.id.tvDevRssi);
                tvDeviceIcon = itemView.findViewById(R.id.tvDeviceIcon);
                btnServiceDetail = itemView.findViewById(R.id.btnServiceDetail);
            }
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(requireContext()).inflate(R.layout.item_ble_device, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            BleManager.ScanDeviceInfo dev = deviceList.get(pos);
            String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";
            h.tvDevName.setText(name);
            h.tvDevAddress.setText(dev.address);
            h.tvDevRssi.setText(formatRssi(dev.rssi));

            String lowerName = (dev.name != null ? dev.name : "").toLowerCase(Locale.ROOT);
            if (lowerName.contains("heart") || lowerName.contains("watch") || lowerName.contains("band")) {
                h.tvDeviceIcon.setText("❤️");
            } else if (lowerName.contains("mi") || lowerName.contains("xiaomi") || lowerName.contains("小米")) {
                h.tvDeviceIcon.setText("⌚");
            } else if (lowerName.contains("iqoo") || lowerName.contains("vivo")) {
                h.tvDeviceIcon.setText("⌚");
            } else {
                h.tvDeviceIcon.setText("📡");
            }

            if (broadSearch) {
                h.btnServiceDetail.setVisibility(View.VISIBLE);
                h.btnServiceDetail.setOnClickListener(v -> showServiceMenu(dev));
            } else {
                h.btnServiceDetail.setVisibility(View.GONE);
                h.btnServiceDetail.setOnClickListener(null);
            }

            h.itemView.setOnClickListener(v -> {
                BleManager ble = serviceLocator.getBleManager();
                if (ble == null) return;
                if (isScanning) {
                    Toast.makeText(getActivity(), "请先停止扫描再连接", Toast.LENGTH_SHORT).show();
                    appendLog("⚠️ 请先点击「停止扫描」再连接", 0xFFffaa33);
                    return;
                }
                if (ble.isConnected()) {
                    if (ble.getCurrentDeviceAddress() != null && ble.getCurrentDeviceAddress().equals(dev.address)) {
                        appendLog("ℹ️ 已连接此设备", 0xFFcbadbb);
                        Toast.makeText(getActivity(), "已连接 " + name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 已连接其他设备，先断开
                    ble.disconnect();
                    appendLog("🔌 已断开当前设备，切换到 " + name, 0xFFffaa33);
                }
                appendLog("🔗 正在连接: " + name + " (" + dev.address + ")", 0xFFcbadbb);
                ble.connectToDevice(dev.address, dev.name);
                updateStatus("📡 " + name, "连接中...");
            });
        }

        @Override
        public int getItemCount() { return deviceList.size(); }
    }

    private void showServiceMenu(BleManager.ScanDeviceInfo dev) {
        if (getContext() == null) return;
        String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";
        new AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setItems(new String[]{"📋 查看广播服务 UUID", "🔍 筛选模式（自动匹配心率特征）"}, (dialog, which) -> {
                if (which == 0) showServiceUuidDialog(dev);
                else startFilterMode(dev);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showServiceUuidDialog(BleManager.ScanDeviceInfo dev) {
        if (getContext() == null) return;
        String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";
        List<String> uuids = dev.serviceUuids;

        StringBuilder sb = new StringBuilder();
        sb.append("📡 ").append(name).append("\n");
        sb.append("📍 ").append(dev.address).append("\n");
        sb.append("📶 ").append(formatRssi(dev.rssi)).append("\n\n");

        if (uuids == null || uuids.isEmpty()) {
            sb.append("该设备广播中未声明 Service UUID\n");
            sb.append("（可能需要在连接后通过服务发现获取）");
        } else {
            sb.append("广播的 Service UUID（共 ").append(uuids.size()).append(" 个）:\n\n");
            for (int i = 0; i < uuids.size(); i++) {
                String uuid = uuids.get(i);
                String tag = "";
                if (uuid.contains("180d")) tag = " ← 心率服务(标准)";
                else if (uuid.contains("180a")) tag = " ← 设备信息";
                else if (uuid.contains("180f")) tag = " ← 电池服务";
                else if (uuid.contains("fee0")) tag = " ← 小米/华米私有";
                else if (uuid.contains("fee1")) tag = " ← 小米/华米私有";
                sb.append("  ").append(i + 1).append(". ").append(uuid).append(tag).append("\n");
            }
            sb.append("\n💡 小米手环通常使用 fee0/fee1 等私有服务");
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("📋 服务 UUID 详情")
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .setNegativeButton("连接此设备", (d, w) -> {
                BleManager ble = serviceLocator.getBleManager();
                if (ble != null) {
                    if (isScanning) {
                        ble.stopManualScan();
                        isScanning = false;
                        btnScan.setText("🔍 开始搜索");
                    }
                    appendLog("🔗 正在连接: " + name, 0xFFcbadbb);
                    ble.connectToDevice(dev.address, dev.name);
                    updateStatus("📡 " + name, "连接中...");
                }
            })
            .show();
    }

    private void startFilterMode(BleManager.ScanDeviceInfo dev) {
        BleManager ble = serviceLocator.getBleManager();
        if (ble == null) return;

        String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";
        appendLog("🔍 [筛选模式] 开始分析 " + name + " ...", 0xFF4CAF50);
        appendLog("🔍 [筛选模式] 正在连接并发现所有服务...", 0xFFcbadbb);

        if (isScanning) {
            ble.stopManualScan();
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
        }

        handler.removeCallbacks(scanTimeoutRunnable);

        ble.connectAndFilterServices(dev.address, dev.name, new BleManager.FilterCallback() {
            @Override
            public void onServiceDiscovered(String serviceUuid, String charUuid) {
                handler.post(() -> appendLog("🔍 [筛选] 发现服务: " + serviceUuid + " / 特征: " + charUuid, 0xFFcbadbb));
            }
            @Override
            public void onHeartRateCharFound(String serviceUuid, String charUuid) {
                handler.post(() -> appendLog("✅ [筛选] 匹配到心率特征! 服务=" + serviceUuid + " 特征=" + charUuid, 0xFF4CAF50));
            }
            @Override
            public void onSubscribed(String serviceUuid, String charUuid) {
                handler.post(() -> {
                    appendLog("✅ [筛选] 已订阅通知: " + charUuid + " → 等待心率数据...", 0xFF4CAF50);
                    updateStatus(name, "已连接（筛选模式）");
                });
                if (ble != null) ble.saveLastDevice(dev.name, dev.address);
                HeartEventBus.getInstance().post(HeartEventBus.EVENT_CONNECTED, name);
            }
            @Override
            public void onFilterComplete(int totalServices, int totalChars, int matched) {
                handler.post(() -> {
                    appendLog("🔍 [筛选] 扫描完成: " + totalServices + " 个服务, " + totalChars + " 个特征, 匹配到 " + matched + " 个心率相关特征", 0xFFcbadbb);
                    if (matched == 0) {
                        appendLog("⚠️ [筛选] 未找到匹配的心率特征 — 可以查看「广播服务UUID」手动选择", 0xFFffaa33);
                        updateStatus(name, "未匹配到心率特征");
                    }
                });
            }
            @Override
            public void onError(String msg) {
                handler.post(() -> {
                    appendLog("❌ [筛选] " + msg, 0xFFff5d7c);
                    updateStatus(name, "筛选失败");
                });
            }
            @Override
            public void onHeartRateData(int hr) {
                handler.post(() -> appendLog("❤️ 心率数据: " + hr + " BPM", 0xFFff5d7c));
            }
        });
    }

    public static String formatRssi(int rssi) {
        int bars;
        if (rssi >= -50) bars = 5;
        else if (rssi >= -65) bars = 4;
        else if (rssi >= -80) bars = 3;
        else if (rssi >= -90) bars = 2;
        else if (rssi >= -100) bars = 1;
        else bars = 0;
        return bars + "/5📶";
    }

    public void onScanResult(List<BleManager.ScanDeviceInfo> devices) {
        handler.post(() -> {
            deviceList.clear();
            deviceList.addAll(devices);
            sortDeviceList();
            deviceAdapter.notifyDataSetChanged();
            tvScanCount.setText("共 " + devices.size() + " 个");
        });
    }

    private void sortDeviceList() {
        deviceList.sort((a, b) -> {
            int pa = getDevicePriority(a);
            int pb = getDevicePriority(b);
            if (pa != pb) return Integer.compare(pb, pa);
            return Integer.compare(b.rssi, a.rssi);
        });
    }

    private int getDevicePriority(BleManager.ScanDeviceInfo dev) {
        if (dev.name == null || dev.name.isEmpty() || "未知设备".equals(dev.name)) return 0;
        String n = dev.name.toLowerCase(Locale.ROOT);
        if (n.contains("heart")) return 100;
        if (n.contains("watch") || n.contains("band")) return 80;
        if (n.contains("mi ") || n.contains("xiaomi") || n.contains("小米")
            || n.contains("华为") || n.contains("huawei")
            || n.contains("honor") || n.contains("荣耀")
            || n.contains("三星") || n.contains("samsung")
            || n.contains("oppo") || n.contains("vivo")
            || n.contains("iqoo")) return 70;
        return 50;
    }

    public void onScanStopped() {
        handler.post(() -> {
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
            if (deviceList.isEmpty()) {
                appendLog("📭 未发现任何设备", 0xFF675c62);
            } else {
                appendLog("✅ 扫描完成，共发现 " + deviceList.size() + " 个设备", 0xFFcbadbb);
            }
        });
    }

    public void updateStatus(String device, String status) {
        handler.post(() -> {
            if (tvDevice != null) tvDevice.setText(device);
            if (tvStatus != null) tvStatus.setText(status);
        });
    }

    public void appendLogExternal(String text, int color) {
        handler.post(() -> appendLog(text, color));
    }
}