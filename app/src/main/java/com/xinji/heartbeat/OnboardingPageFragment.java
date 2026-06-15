package com.xinji.heartbeat;
import android.animation.*;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
public class OnboardingPageFragment extends Fragment {
    private static final String ARG_POS = "pos";
    public static OnboardingPageFragment newInstance(int position) {
        OnboardingPageFragment f = new OnboardingPageFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_POS, position);
        f.setArguments(b);
        return f;
    }
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.item_onboarding_page, container, false);
        int pos = getArguments() != null ? getArguments().getInt(ARG_POS, 0) : 0;
        TextView icon = v.findViewById(R.id.icon);
        TextView title = v.findViewById(R.id.title);
        TextView desc = v.findViewById(R.id.desc);
        TextView btnAction = v.findViewById(R.id.btnAction);
        androidx.fragment.app.FragmentActivity act = getActivity();
        if (act instanceof OnboardingActivity) {
            OnboardingActivity.PageData data = ((OnboardingActivity) act).getPageData(pos);
            if (data != null) {
                icon.setText(data.icon);
                title.setText(data.title);
                desc.setText(data.desc);
                // 权限页：显示授权按钮，点击后请求蓝牙权限
                if ("permission".equals(data.type)) {
                    btnAction.setVisibility(View.VISIBLE);
                    switch (pos) {
                        case 2:
                            btnAction.setText("📍 授予位置权限");
                            btnAction.setOnClickListener(v2 -> ((OnboardingActivity) act).requestBluetoothPermissions());
                            break;
                        case 3:
                            btnAction.setText("🖥️ 授予悬浮窗权限");
                            btnAction.setOnClickListener(v2 -> ((OnboardingActivity) act).requestOverlayPermission());
                            break;
                        case 4:
                            btnAction.setText("🔔 授予通知权限");
                            btnAction.setOnClickListener(v2 -> ((OnboardingActivity) act).requestNotificationPermission());
                            break;
                        case 5:
                            btnAction.setText("🔋 授予电池优化");
                            btnAction.setOnClickListener(v2 -> ((OnboardingActivity) act).requestBatteryPermission());
                            break;
                    }
                } else {
                    btnAction.setVisibility(View.GONE);
                }
            }
        }
        v.setBackgroundColor(0xFF0c0c10);
        v.post(() -> animateIn(v, icon, title, desc));
        return v;
    }
    private void animateIn(View root, TextView icon, TextView title, TextView desc) {
        icon.setTranslationY(60f); icon.setAlpha(0f);
        title.setTranslationY(40f); title.setAlpha(0f);
        desc.setTranslationY(30f); desc.setAlpha(0f);
        icon.animate().translationY(0).alpha(1).setDuration(600).setStartDelay(100)
            .setInterpolator(new DecelerateInterpolator()).start();
        title.animate().translationY(0).alpha(1).setDuration(500).setStartDelay(350)
            .setInterpolator(new DecelerateInterpolator()).start();
        desc.animate().translationY(0).alpha(1).setDuration(500).setStartDelay(550)
            .setInterpolator(new DecelerateInterpolator()).start();
        ObjectAnimator px = ObjectAnimator.ofFloat(icon, "scaleX", 1f, 1.15f, 0.95f, 1.05f, 1f);
        ObjectAnimator py = ObjectAnimator.ofFloat(icon, "scaleY", 1f, 1.15f, 0.95f, 1.05f, 1f);
        AnimatorSet pulse = new AnimatorSet();
        pulse.playTogether(px, py);
        pulse.setDuration(900); pulse.setStartDelay(200);
        pulse.setInterpolator(new DecelerateInterpolator());
        pulse.start();
    }
}
