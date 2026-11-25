package com.xinji.heartbeat.widget;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public final class Interactions {

    private static final float PRESSED_SCALE = 0.97f;
    private static final long DURATION = 100L;

    private Interactions() {}

    public static void press(View v) {
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        animate(view, PRESSED_SCALE);
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        animate(view, 1f);
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    public static void pressAll(View... views) {
        if (views == null) return;
        for (View v : views) press(v);
    }

    public static void selection(View v) {
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                return false;
            }
        });
    }

    private static void animate(View v, float scale) {
        v.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(DURATION)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }
}
