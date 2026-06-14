package com.xinji.heartbeat;
import android.animation.*;
import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.*;
import android.widget.TextView;
public class SplashActivity extends Activity {
    private static final long ANIM_DURATION = 2600L;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_splash);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getDecorView().post(() -> {
                try {
                    getWindow().setDecorFitsSystemWindows(false);
                    getWindow().getInsetsController().hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    getWindow().getInsetsController().setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } catch (Exception ignored) {}
            });
        }
        TextView heartIcon = findViewById(R.id.heartIcon);
        TextView textTitle = findViewById(R.id.textTitle);
        TextView textSubtitle = findViewById(R.id.textSubtitle);
        TextView textVersion = findViewById(R.id.textVersion);
        heartIcon.setTranslationY(40f); heartIcon.setAlpha(0f);
        textTitle.setTranslationY(30f); textTitle.setAlpha(0f);
        textSubtitle.setTranslationY(20f); textSubtitle.setAlpha(0f);
        textVersion.setAlpha(0f);
        heartIcon.animate().translationY(0).alpha(1).setDuration(700)
            .setInterpolator(new DecelerateInterpolator()).start();
        textTitle.animate().translationY(0).alpha(1).setDuration(500)
            .setStartDelay(400).setInterpolator(new DecelerateInterpolator()).start();
        textSubtitle.animate().translationY(0).alpha(1).setDuration(500)
            .setStartDelay(700).setInterpolator(new DecelerateInterpolator()).start();
        textVersion.animate().alpha(1).setDuration(400)
            .setStartDelay(1100).start();
        AnimatorSet pulse = new AnimatorSet();
        pulse.playTogether(
            ObjectAnimator.ofFloat(heartIcon, "scaleX", 0.8f, 1.2f, 0.9f, 1.05f, 1f),
            ObjectAnimator.ofFloat(heartIcon, "scaleY", 0.8f, 1.2f, 0.9f, 1.05f, 1f));
        pulse.setDuration(1000); pulse.setStartDelay(200);
        pulse.setInterpolator(new DecelerateInterpolator());
        pulse.start();
        View root = findViewById(R.id.splashRoot);
        root.animate().alpha(0).setDuration(400).setStartDelay(ANIM_DURATION - 400)
            .withEndAction(this::goToMain).start();
        root.setOnClickListener(v -> {
            root.animate().cancel();
            goToMain();
        });
    }
    private void goToMain() {
        if (isFinishing()) return;
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
