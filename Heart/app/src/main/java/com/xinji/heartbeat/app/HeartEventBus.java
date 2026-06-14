package com.xinji.heartbeat.app;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class HeartEventBus {

    private static HeartEventBus instance;

    private final CopyOnWriteArrayList<WeakReference<EventListener>> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface EventListener {
        void onEvent(int type, Object data);
    }

    public static final int EVENT_HR_UPDATE = 1;
    public static final int EVENT_CONNECTED = 2;
    public static final int EVENT_DISCONNECTED = 3;
    public static final int EVENT_CONNECTING = 4;
    public static final int EVENT_SCAN_RESULT = 5;
    public static final int EVENT_SCAN_STOPPED = 6;
    public static final int EVENT_SCAN_FAILED = 7;
    public static final int EVENT_CONNECTION_FAILED = 8;
    public static final int EVENT_BLE_NOT_AVAILABLE = 9;
    public static final int EVENT_BROADCAST_STARTED = 10;
    public static final int EVENT_BROADCAST_STOPPED = 11;
    public static final int EVENT_GAME_STATE = 12;
    public static final int EVENT_FLOAT_STYLE_CHANGED = 13;

    private HeartEventBus() {}

    public static synchronized HeartEventBus getInstance() {
        if (instance == null) {
            instance = new HeartEventBus();
        }
        return instance;
    }

    public void register(EventListener listener) {
        if (listener == null) return;
        for (WeakReference<EventListener> ref : listeners) {
            if (ref.get() == listener) return;
        }
        listeners.add(new WeakReference<>(listener));
    }

    public void unregister(EventListener listener) {
        for (WeakReference<EventListener> ref : listeners) {
            EventListener l = ref.get();
            if (l == null || l == listener) {
                listeners.remove(ref);
            }
        }
    }

    public void post(int type, Object data) {
        Iterator<WeakReference<EventListener>> it = listeners.iterator();
        while (it.hasNext()) {
            EventListener listener = it.next().get();
            if (listener == null) {
                it.remove();
                continue;
            }
            try {
                listener.onEvent(type, data);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void postOnMain(int type, Object data) {
        mainHandler.post(() -> post(type, data));
    }

    public void clear() {
        listeners.clear();
    }
}
