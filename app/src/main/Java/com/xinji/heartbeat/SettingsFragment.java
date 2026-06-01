package com.xinji.heartbeat;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.xinji.heartbeat.widget.FloatWindowManager;

/**
 * 设置页面 — 适配重构后的 FloatWindowManager + BroadcastServer。
 */
public class SettingsFragment extends Fragment {
    private MainActivity activity;
    private Switch swAutoConnect, swFloatMemory, swFloatVisible, swFloatLocked;
    private TextView tvFloatStyleName, tvBroadcastStatus;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);
        activity = (MainActivity) getActivity();

        swAutoConnect = v.findViewById(R.id.swAutoConnect);
        swFloatMemory = v.findViewById(R.id.swFloatMemory);
        swFloatVisible = v.findViewById(R.id.swFloatVisible);
        swFloatLocked = v.findViewById(R.id.swFloatLocked);
        tvFloatStyleName = v.findViewById(R.id.tvFloatStyleName);
        tvBroadcastStatus = v.findViewById(R.id.tvBroadcastStatus);

        // 初始状态
        swAutoConnect.setChecked(activity.autoConnectEnabled);
        var floatMgr = activity.getFloatWindowManager();
        swFloatMemory.setChecked(floatMgr.isMemoryEnabled());
        swFloatVisible.setChecked(floatMgr.isVisible());
        swFloatLocked.setChecked(floatMgr.isLocked());
        updateStyleName();
        updateBroadcastStatus();

        // 监听器
        swAutoConnect.setOnCheckedChangeListener((b, c) -> {
            activity.autoConnectEnabled = c;
            activity.prefs.edit().putBoolean("auto_connect", c).apply();
            activity.getBleManager().setAutoConnectEnabled(c);
            if (c) activity.getBleManager().startAutoScan();
        });

        swFloatMemory.setOnCheckedChangeListener((b, c) -> {
            activity.getFloatWindowManager().setMemoryEnabled(c);
            activity.prefs.edit().putBoolean("float_memory", c).apply();
        });

        swFloatVisible.setOnCheckedChangeListener((b, c) -> {
            if (c) {
                activity.getFloatWindowManager().show();
            } else {
                activity.getFloatWindowManager().hideByUser();
            }
        });

        swFloatLocked.setOnCheckedChangeListener((b, c) -> {
            activity.getFloatWindowManager().setLocked(c);
        });

        v.findViewById(R.id.btnFloatStyle).setOnClickListener(view -> showStylePicker());
        v.findViewById(R.id.btnHelp).setOnClickListener(this::showHelp);
        v.findViewById(R.id.btnContact).setOnClickListener(this::contactAuthor);
        v.findViewById(R.id.btnBroadcast).setOnClickListener(this::openBroadcastPage);
        v.findViewById(R.id.btnGitHub).setOnClickListener(v2 -> openGitHub());
        v.findViewById(R.id.btnCheckUpdate).setOnClickListener(v2 -> checkUpdate());

        Switch swHide = v.findViewById(R.id.swHideRecents);
        swHide.setChecked(RecentsUtils.isHideFromRecents(requireContext()));
        swHide.setOnCheckedChangeListener((b, c) -> RecentsUtils.setHideFromRecents(activity, c));

        return v;
    }

    void syncFloatSwitch(boolean visible) {
        if (swFloatVisible != null) swFloatVisible.setChecked(visible);
    }

    void updateBroadcastStatus() {
        if (tvBroadcastStatus == null || activity == null) return;
        var server = activity.getBroadcastServer();
        if (activity.broadcastEnabled && server != null && server.isRunning()) {
            String ip = server.getLocalIP();
            String type = server.getNetworkType();
            if (!"未连接".equals(ip)) {
                tvBroadcastStatus.setText("已启动 · " + type + ":" + ip);
            } else {
                tvBroadcastStatus.setText("⚠️ 已启动，但未检测到网络");
            }
        } else {
            tvBroadcastStatus.setText("未启动 · 点此管理");
        }
    }

    private void openBroadcastPage(View v) {
        Intent intent = new Intent(activity, BroadcastActivity.class);
        startActivity(intent);
    }

    private void updateStyleName() {
        if (tvFloatStyleName == null || activity == null) return;
        int style = activity.prefs.getInt("float_style", 0);
        tvFloatStyleName.setText(FloatWindowManager.STYLE_NAMES[style]);
    }

    private void showStylePicker() {
        if (activity == null) return;
        int current = activity.prefs.getInt("float_style", 0);
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("悬浮窗样式")
            .setSingleChoiceItems(FloatWindowManager.STYLE_NAMES, current, (dialog, which) -> {
                activity.prefs.edit().putInt("float_style", which).apply();
                updateStyleName();
                activity.recreateFloatWindow();
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showHelp(View v) {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("📖 使用帮助")
            .setMessage("1. 开启蓝牙并扫描连接心率设备\n2. 连接成功后主页显示心率\n3. 悬浮窗可在其他应用上显示心率\n4. 自动连接会记忆上次设备并自动重连\n5. 悬浮窗记忆会保存位置和显示状态\n6. 心率广播：将心率推送到电脑显示\n\n问题反馈：QQ 3544399875")
            .setPositiveButton("知道了", null)
            .show();
    }

    private void contactAuthor(View v) {
        try {
            String url = "mqqwpa://im/chat?chat_type=wpa&uin=3544399875";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "请先安装 QQ", Toast.LENGTH_SHORT).show();
        }
    }

    private void openGitHub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/a2006-dev/heart")));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "打开链接失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkUpdate() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/a2006-dev/heart/releases/latest")));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "打开链接失败", Toast.LENGTH_SHORT).show();
        }
    }
}