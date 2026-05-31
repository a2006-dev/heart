package com.xinji.heartbeat;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import android.app.AlertDialog;
import androidx.fragment.app.Fragment;
import android.content.Intent;
public class SettingsFragment extends Fragment {
    private Switch swAutoConnect, swFloatMemory, swFloatVisible, swFloatLocked;
    private TextView tvFloatStyleName, tvBroadcastStatus;
    private MainActivity activity;
    private static final String[] FLOAT_STYLE_NAMES = {"简约文字", "科技胶囊", "圆形徽章", "心电脉搏", "超小迷你"};
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
        swAutoConnect.setChecked(activity.autoConnectEnabled);
        swFloatMemory.setChecked(activity.floatMemoryEnabled);
        swFloatVisible.setChecked(activity.floatVisible);
        swFloatLocked.setChecked(activity.floatLocked);
        updateStyleName();
        updateBroadcastStatus();
        swAutoConnect.setOnCheckedChangeListener((b, c) -> {
            activity.autoConnectEnabled = c;
            activity.prefs.edit().putBoolean("auto_connect", c).apply();
            if (c) activity.startAutoScan();
        });
        swFloatMemory.setOnCheckedChangeListener((b, c) -> {
            activity.floatMemoryEnabled = c;
            activity.prefs.edit().putBoolean("float_memory", c).apply();
        });
        swFloatVisible.setOnCheckedChangeListener((b, c) -> {
            if (c) {
                activity.showFloatWindow();
            } else {
                activity.hideFloatWindowByUser();
            }
        });
        swFloatLocked.setOnCheckedChangeListener((b, c) -> {
            activity.floatLocked = c;
            activity.prefs.edit().putBoolean("float_locked", c).apply();
            activity.updateFloatTouchable();
        });
        v.findViewById(R.id.btnFloatStyle).setOnClickListener(view -> showStylePicker());
        v.findViewById(R.id.btnHelp).setOnClickListener(this::showHelp);
        v.findViewById(R.id.btnContact).setOnClickListener(this::contactAuthor);
        v.findViewById(R.id.btnBroadcast).setOnClickListener(this::openBroadcastPage);
        v.findViewById(R.id.btnGitHub).setOnClickListener(v2 -> openGitHub());
        return v;
    }
    void syncFloatSwitch(boolean visible) {
        if (swFloatVisible != null) swFloatVisible.setChecked(visible);
    }
    void updateBroadcastStatus() {
        if (tvBroadcastStatus == null) return;
        if (activity.broadcastEnabled && activity.broadcastServer != null && activity.broadcastServer.isRunning()) {
            String ip = HeartRateBroadcastServer.getLocalIP(requireContext());
            String type = HeartRateBroadcastServer.getNetworkType(requireContext());
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
        if (tvFloatStyleName == null) return;
        int style = activity.prefs.getInt("float_style", 0);
        tvFloatStyleName.setText(FLOAT_STYLE_NAMES[style]);
    }
    private void showStylePicker() {
        int current = activity.prefs.getInt("float_style", 0);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("悬浮窗样式")
            .setSingleChoiceItems(FLOAT_STYLE_NAMES, current, (dialog, which) -> {
                activity.prefs.edit().putInt("float_style", which).apply();
                updateStyleName();
                activity.recreateFloatWindow();
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    private void showHelp(View v) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
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

    /** 打开 GitHub 仓库 */
    private void openGitHub() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/a2006-dev/heart"));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "打开链接失败", Toast.LENGTH_SHORT).show();
        }
    }
}
