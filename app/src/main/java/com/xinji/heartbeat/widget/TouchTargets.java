package com.xinji.heartbeat.widget;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewParent;
import com.xinji.heartbeat.R;

public final class TouchTargets {

    private TouchTargets() {}

    public static void expand(View v, int extraDp, float density) {
        if (v == null) return;
        expand(v, (int) (extraDp * density));
    }

    public static void expand(final View v, final int extraPx) {
        if (v == null || extraPx <= 0) return;
        final ViewParent parent = v.getParent();
        if (!(parent instanceof View)) return;
        final View container = (View) parent;
        container.post(new Runnable() {
            @Override public void run() {
                Rect rect = new Rect();
                v.getHitRect(rect);
                rect.top -= extraPx;
                rect.bottom += extraPx;
                rect.left -= extraPx;
                rect.right += extraPx;
                TouchDelegateGroup group = container.getTag(R.id.tag_touch_group) instanceof TouchDelegateGroup
                    ? (TouchDelegateGroup) container.getTag(R.id.tag_touch_group) : null;
                if (group == null) {
                    group = new TouchDelegateGroup();
                    container.setTag(R.id.tag_touch_group, group);
                    container.setTouchDelegate(group);
                }
                group.add(new TouchDelegate(rect, v));
            }
        });
    }

    public static final class TouchDelegateGroup extends TouchDelegate {
        private final java.util.List<TouchDelegate> delegates = new java.util.ArrayList<>();

        TouchDelegateGroup() {
            super(new Rect(), null);
        }

        void add(TouchDelegate d) {
            if (d != null) delegates.add(d);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            for (int i = 0; i < delegates.size(); i++) {
                if (delegates.get(i).onTouchEvent(event)) return true;
            }
            return false;
        }
    }
}