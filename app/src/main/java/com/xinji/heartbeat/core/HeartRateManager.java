package com.xinji.heartbeat.core;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

public class HeartRateManager {
    private static volatile HeartRateManager instance;
    private Context context;
    private final CopyOnWriteArrayList<WeakReference<HeartRateListener>> listeners = new CopyOnWriteArrayList<>();

    private volatile int currentHR = 0;
    private volatile long lastHRTime = 0;
    private Handler timeoutHandler;
    private TimeoutListener timeoutListener;
    private static final long TIMEOUT_MS = 5 * 60 * 1000;

    public interface HeartRateListener {
        void onHeartRateChanged(int hr);
    }

    public interface TimeoutListener {
        void onHeartRateTimeout();
    }

    private HeartRateManager(Context context) {
        this.context = context.getApplicationContext();
        this.timeoutHandler = new Handler(Looper.getMainLooper());
    }

    public static HeartRateManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HeartRateManager.class) {
                if (instance == null) {
                    instance = new HeartRateManager(context);
                }
            }
        }
        return instance;
    }

    public int getCurrentHR() {
        return currentHR;
    }

    public long getLastHRTime() {
        return lastHRTime;
    }

    public void setTimeoutListener(TimeoutListener listener) {
        this.timeoutListener = listener;
    }

    public void startTimeoutCheck() {
        timeoutHandler.removeCallbacksAndMessages(null);
        timeoutHandler.postDelayed(timeoutCheck, TIMEOUT_MS);
    }

    public void stopTimeoutCheck() {
        timeoutHandler.removeCallbacksAndMessages(null);
    }

    private final Runnable timeoutCheck = () -> {
        if (lastHRTime > 0 && System.currentTimeMillis() - lastHRTime >= TIMEOUT_MS) {
            if (timeoutListener != null) {
                timeoutListener.onHeartRateTimeout();
            }
        }
    };

    public void registerListener(HeartRateListener listener) {
        if (listener == null) return;
        for (WeakReference<HeartRateListener> ref : listeners) {
            HeartRateListener existing = ref.get();
            if (existing == listener) return;
        }
        listeners.add(new WeakReference<>(listener));
    }

    public void removeListener(HeartRateListener listener) {
        Iterator<WeakReference<HeartRateListener>> it = listeners.iterator();
        while (it.hasNext()) {
            HeartRateListener l = it.next().get();
            if (l == null || l == listener) {
                it.remove();
            }
        }
    }

    @Deprecated
    public void removeAllListeners() {
        listeners.clear();
    }

    public void notifyListeners(int hr) {
        this.currentHR = hr;
        if (hr > 20 && hr < 250) {
            this.lastHRTime = System.currentTimeMillis();
        }
        Iterator<WeakReference<HeartRateListener>> it = listeners.iterator();
        while (it.hasNext()) {
            HeartRateListener listener = it.next().get();
            if (listener == null) {
                it.remove();
                continue;
            }
            try {
                listener.onHeartRateChanged(hr);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
