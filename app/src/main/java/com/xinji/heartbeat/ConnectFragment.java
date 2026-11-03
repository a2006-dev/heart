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
    private List<BleManager.ScanDeviceInfo> pairedList = new ArrayList<>();
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
        loadPairedDevices();
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
        if (currentDetector != null) {
            currentDetector.stop();
            currentDetector = null;
        }
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

    class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_PAIRED_HEADER = 0;
        private static final int TYPE_PAIRED_DEVICE = 1;
        private static final int TYPE_SCAN_HEADER = 2;
        private static final int TYPE_SCAN_DEVICE = 3;

        private boolean hasPaired() { return !pairedList.isEmpty(); }
        private boolean hasScan() { return !deviceList.isEmpty(); }

        @Override
        public int getItemViewType(int pos) {
            if (hasPaired()) {
                if (pos == 0) return TYPE_PAIRED_HEADER;
                if (pos < pairedList.size() + 1) return TYPE_PAIRED_DEVICE;
                int scanStart = pairedList.size() + 1;
                if (hasScan()) {
                    if (pos == scanStart) return TYPE_SCAN_HEADER;
                    return TYPE_SCAN_DEVICE;
                }
            } else if (hasScan()) {
                if (pos == 0) return TYPE_SCAN_HEADER;
                return TYPE_SCAN_DEVICE;
            }
            return TYPE_SCAN_DEVICE;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(requireContext());
            if (viewType == TYPE_PAIRED_HEADER || viewType == TYPE_SCAN_HEADER) {
                TextView tv = new TextView(requireContext());
                tv.setPadding(24, 16, 24, 8);
                tv.setTextSize(13);
                tv.setTextColor(0xFF7a6a70);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                return new RecyclerView.ViewHolder(tv) {};
            }
            View v = inflater.inflate(R.layout.item_ble_device, parent, false);
            return new DeviceViewHolder(v);
        }

        class DeviceViewHolder extends RecyclerView.ViewHolder {
            TextView tvDevName, tvDevAddress, tvDevRssi, tvDeviceIcon, btnServiceDetail;
            DeviceViewHolder(@NonNull View itemView) {
                super(itemView);
                tvDevName = itemView.findViewById(R.id.tvDevName);
                tvDevAddress = itemView.findViewById(R.id.tvDevAddress);
                tvDevRssi = itemView.findViewById(R.id.tvDevRssi);
                tvDeviceIcon = itemView.findViewById(R.id.tvDeviceIcon);
                btnServiceDetail = itemView.findViewById(R.id.btnServiceDetail);
            }
        }

        private BleManager.ScanDeviceInfo getItem(int pos) {
            if (hasPaired()) {
                if (pos == 0 || pos == pairedList.size() + 1) return null;
                if (pos < pairedList.size() + 1) return pairedList.get(pos - 1);
                return deviceList.get(pos - pairedList.size() - 2);
            }
            if (pos == 0) return null;
            return deviceList.get(pos - 1);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            int type = getItemViewType(pos);
            if (type == TYPE_PAIRED_HEADER) {
                ((TextView) h.itemView).setText("📋 已配对设备 · 长按删除");
                return;
            }
            if (type == TYPE_SCAN_HEADER) {
                ((TextView) h.itemView).setText("📡 搜索到的设备");
                return;
            }

            DeviceViewHolder dh = (DeviceViewHolder) h;
            BleManager.ScanDeviceInfo dev = getItem(pos);
            if (dev == null) return;
            String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";
            dh.tvDevName.setText(type == TYPE_PAIRED_DEVICE ? "🔗 " + name : name);
            dh.tvDevAddress.setText(dev.address);
            dh.tvDevRssi.setText(formatRssi(dev.rssi));

            String lowerName = (dev.name != null ? dev.name : "").toLowerCase(Locale.ROOT);
            if (lowerName.contains("heart") || lowerName.contains("watch") || lowerName.contains("band")) {
                dh.tvDeviceIcon.setText("❤️");
            } else if (lowerName.contains("mi") || lowerName.contains("xiaomi") || lowerName.contains("小米")) {
                dh.tvDeviceIcon.setText("⌚");
            } else if (lowerName.contains("iqoo") || lowerName.contains("vivo")) {
                dh.tvDeviceIcon.setText("⌚");
            } else {
                dh.tvDeviceIcon.setText("📡");
            }

            if (broadSearch) {
                dh.btnServiceDetail.setVisibility(View.VISIBLE);
                dh.btnServiceDetail.setOnClickListener(v -> showServiceMenu(dev));
            } else {
                dh.btnServiceDetail.setVisibility(View.GONE);
                dh.btnServiceDetail.setOnClickListener(null);
            }

            dh.itemView.setOnClickListener(v -> {
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
                    ble.disconnect();
                    appendLog("🔌 已断开当前设备，切换到 " + name, 0xFFffaa33);
                }
                appendLog("🔗 正在连接: " + name + " (" + dev.address + ")", 0xFFcbadbb);
                ble.connectToDevice(dev.address, dev.name);
                updateStatus("📡 " + name, "连接中...");
            });


            dh.itemView.setOnLongClickListener(v -> {
                if (getContext() == null) return false;
                if (type != TYPE_PAIRED_DEVICE) return false;
                new AlertDialog.Builder(requireContext())
                    .setTitle("🗑️ 取消配对")
                    .setMessage("确定要忘记「" + name + "」吗？")
                    .setPositiveButton("确定", (d, w) -> unpairDevice(dev))
                    .setNegativeButton("取消", null)
                    .show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            int count = 0;
            if (hasPaired()) count += pairedList.size() + 1;
            if (hasScan()) count += deviceList.size() + 1;
            return count;
        }
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

    private com.xinji.heartbeat.bluetooth.HeartRateDetector currentDetector;

    private void startFilterMode(BleManager.ScanDeviceInfo dev) {
        String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";
        appendLog("🔍 [手动检测] 连接 " + name + " 并列出所有特征...", 0xFF4CAF50);
        if (isScanning) {
            BleManager ble = serviceLocator.getBleManager();
            if (ble != null) ble.stopManualScan();
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
        }
        handler.removeCallbacks(scanTimeoutRunnable);


        BleManager ble = serviceLocator.getBleManager();
        if (ble != null) {
            if (ble.isConnected()) {
                ble.disconnect();
                appendLog("🔌 已断开当前连接", 0xFFffaa33);
            }
        }

        appendLog("🔗 正在连接: " + name, 0xFFcbadbb);
        updateStatus("📡 " + name, "检测中...");


        currentDetector = new com.xinji.heartbeat.bluetooth.HeartRateDetector(
                requireContext(), dev.address, dev.name,
                new com.xinji.heartbeat.bluetooth.HeartRateDetector.DetectCallback() {
                    @Override
                    public void onConnecting() {}

                    @Override
                    public void onDataReceived(String svcUuid, String charUuid, byte[] data) {
                        StringBuilder hex = new StringBuilder();
                        for (byte b : data) hex.append(String.format("%02X ", b));
                        handler.post(() -> appendLog("📥 " + (charUuid.length() > 8 ? charUuid.substring(4, 8) : charUuid) + " → " + hex.toString().trim(), 0xFF4CAF50));
                    }

                    @Override
                    public void onResult(java.util.List<com.xinji.heartbeat.bluetooth.DetectResult> results) {
                        handler.post(() -> showCharPicker(results, dev));
                    }

                    @Override
                    public void onError(String reason) {
                        handler.post(() -> {
                            appendLog("❌ " + reason, 0xFFff5d7c);
                            updateStatus(name, "检测失败");
                        });
                    }
                });
        currentDetector.start();
    }


    private void showCharPicker(java.util.List<com.xinji.heartbeat.bluetooth.DetectResult> results,
                                 BleManager.ScanDeviceInfo dev) {
        if (results == null || results.isEmpty()) {
            appendLog("⚠️ 未检测到任何特征", 0xFFffaa33);
            return;
        }


        java.util.List<com.xinji.heartbeat.bluetooth.DetectResult> active = new java.util.ArrayList<>();
        for (com.xinji.heartbeat.bluetooth.DetectResult r : results) {
            if (r.rawSamples != null && !r.rawSamples.isEmpty()) active.add(r);
        }
        if (active.isEmpty()) {
            appendLog("⚠️ 连接成功但未收到数据，尝试重新检测", 0xFFffaa33);
            return;
        }


        active.sort((a, b) -> b.confidence.compareTo(a.confidence));

        String[] items = new String[active.size()];
        String[] svcUuids = new String[active.size()];
        String[] charUuids = new String[active.size()];

        for (int i = 0; i < active.size(); i++) {
            com.xinji.heartbeat.bluetooth.DetectResult r = active.get(i);
            String shortSvc = r.serviceUuid.length() > 8 ? r.serviceUuid.substring(4, 8) : r.serviceUuid;
            String shortChar = r.charUuid.length() > 8 ? r.charUuid.substring(4, 8) : r.charUuid;
            int n = r.rawSamples != null ? r.rawSamples.size() : 0;
            byte[] last = r.rawSamples.get(r.rawSamples.size() - 1);
            StringBuilder hex = new StringBuilder();
            for (byte b : last) hex.append(String.format("%02X ", b));
            String conf = r.confidence == com.xinji.heartbeat.bluetooth.DetectResult.Confidence.HIGH ? "✅" :
                          r.confidence == com.xinji.heartbeat.bluetooth.DetectResult.Confidence.MEDIUM ? "⚠️" : "❓";
            String hint = r.candidateHr > 0 ? " ~" + r.candidateHr + "bpm" : "";
            items[i] = conf + " " + shortSvc + "/" + shortChar + hint + " (" + n + "包) " + hex.toString().trim();
            svcUuids[i] = r.serviceUuid;
            charUuids[i] = r.charUuid;
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("📋 选择心率特征")
            .setMessage("检测到 " + active.size() + " 个有数据的特征，\n请选择发送心率数据的那个：")
            .setItems(items, (dialog, which) -> {
                String svc = svcUuids[which];
                String ch = charUuids[which];
                appendLog("✅ 已选择: " + svc + "/" + ch, 0xFF4CAF50);

                try {
                    DeviceProfileManager mgr = serviceLocator.getDeviceProfileManager();
                    if (mgr != null) mgr.addOrUpdateProfile(dev.name, dev.address, svc, ch);
                } catch (Exception ignored) {}
                BleManager ble = serviceLocator.getBleManager();
                if (ble != null) {
                    ble.saveLastDevice(dev.name, dev.address);
                    ble.connectToDevice(dev.address, dev.name);
                    updateStatus("📡 " + (dev.name != null ? dev.name : ""), "连接中...");
                }
            })
            .setNegativeButton("取消", null)
            .show();
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

            java.util.Set<String> pairedAddrs = new java.util.HashSet<>();
            for (BleManager.ScanDeviceInfo p : pairedList) {
                pairedAddrs.add(p.address);
            }
            for (BleManager.ScanDeviceInfo d : devices) {
                if (!pairedAddrs.contains(d.address)) {
                    deviceList.add(d);
                }
            }
            sortDeviceList();
            loadPairedDevices();
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


    private void loadPairedDevices() {
        pairedList.clear();
        try {

            BleManager ble = serviceLocator.getBleManager();
            if (ble != null) {
                java.util.Map<String, String> paired = ble.getPairedDevices();
                for (java.util.Map.Entry<String, String> entry : paired.entrySet()) {
                    BleManager.ScanDeviceInfo info = new BleManager.ScanDeviceInfo(entry.getValue(), entry.getKey(), -100);
                    info.isKnown = true;
                    pairedList.add(info);
                }
            }

            try {
                com.xinji.heartbeat.core.DeviceProfileManager mgr = serviceLocator.getDeviceProfileManager();
                if (mgr != null) {
                    java.util.List<com.xinji.heartbeat.core.DeviceProfileManager.DeviceProfile> profiles = mgr.getProfiles();
                    if (profiles != null) {
                        for (com.xinji.heartbeat.core.DeviceProfileManager.DeviceProfile p : profiles) {
                            if (p.address != null && p.name != null && !p.address.isEmpty()) {

                                boolean exists = false;
                                for (BleManager.ScanDeviceInfo info : pairedList) {
                                    if (info.address.equals(p.address)) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists) {
                                    BleManager.ScanDeviceInfo info = new BleManager.ScanDeviceInfo(p.name, p.address, -100);
                                    info.isKnown = true;
                                    pairedList.add(info);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        deviceAdapter.notifyDataSetChanged();
    }


    private void unpairDevice(BleManager.ScanDeviceInfo dev) {
        BleManager ble = serviceLocator.getBleManager();
        if (ble != null) {
            if (ble.isConnected() && dev.address != null && dev.address.equals(ble.getCurrentDeviceAddress())) {
                ble.disconnect();
            }
            ble.clearLastDevice();
        }

        try {
            DeviceProfileManager mgr = serviceLocator.getDeviceProfileManager();
            if (mgr != null && dev.address != null) {
                mgr.removeProfileByAddress(dev.address);
            }
        } catch (Exception ignored) {}
        pairedList.remove(dev);
        deviceAdapter.notifyDataSetChanged();
        appendLog("🗑️ 已取消配对: " + (dev.name != null ? dev.name : dev.address), 0xFFffaa33);
        Toast.makeText(getActivity(), "已忘记 " + (dev.name != null ? dev.name : dev.address), Toast.LENGTH_SHORT).show();
    }

    private int getDevicePriority(BleManager.ScanDeviceInfo dev) {
        if (dev.name == null || dev.name.isEmpty() || "未知设备".equals(dev.name)) return 0;

        if (dev.isKnown) return 200;
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
