package com.xinji.heartbeat;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 心率管理单例 — 使用弱引用持有监听器，防止 Activity/Service 泄漏。
 * 参考：https://github.com/milirstudio/xinxiu（心宿蓝牙模块）
 */
public class HeartRateManager {
    private static HeartRateManager instance;
    private Context context;
    private final List<WeakReference<HeartRateListener>> listeners = new CopyOnWriteArrayList<>();

    public interface HeartRateListener {
        void onHeartRateChanged(int hr);
    }

    private HeartRateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized HeartRateManager getInstance(Context context) {
        if (instance == null) {
            instance = new HeartRateManager(context);
        }
        return instance;
    }

    /**
     * 注册监听器（内部以 WeakReference 持有，不再需要手动 remove）
     */
    public void registerListener(HeartRateListener listener) {
        // 避免重复注册
        for (WeakReference<HeartRateListener> ref : listeners) {
            HeartRateListener existing = ref.get();
            if (existing == listener || existing == null) continue;
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

    /**
     * 通知所有监听器，自动清理已被 GC 的弱引用。
     */
    public void notifyListeners(int hr) {
        Iterator<WeakReference<HeartRateListener>> it = listeners.iterator();
        while (it.hasNext()) {
            HeartRateListener listener = it.next().get();
            if (listener == null) {
                it.remove(); // 已被回收，清理
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
