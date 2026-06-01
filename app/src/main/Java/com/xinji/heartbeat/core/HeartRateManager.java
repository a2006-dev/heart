package com.xinji.heartbeat.core;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 心率管理单例 — 使用弱引用持有监听器，防止 Activity/Service 泄漏。
 * 
 * 改进：
 * - 支持设置/获取当前心率值
 * - notifyListeners 中捕获异常防止一个监听器崩溃影响所有
 * - 使用 CopyOnWriteArrayList 确保线程安全
 * 
 * 参考：https://github.com/milirstudio/xinxiu（心宿蓝牙模块）
 */
public class HeartRateManager {
    private static volatile HeartRateManager instance;
    private Context context;
    private final CopyOnWriteArrayList<WeakReference<HeartRateListener>> listeners = new CopyOnWriteArrayList<>();

    /** 当前最新心率值，跨线程可见 */
    private volatile int currentHR = 0;

    public interface HeartRateListener {
        void onHeartRateChanged(int hr);
    }

    private HeartRateManager(Context context) {
        this.context = context.getApplicationContext();
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

    /** 获取最新心率值 */
    public int getCurrentHR() {
        return currentHR;
    }

    /**
     * 注册监听器（内部以 WeakReference 持有，不再需要手动 remove）
     */
    public void registerListener(HeartRateListener listener) {
        if (listener == null) return;
        // 避免重复注册
        for (WeakReference<HeartRateListener> ref : listeners) {
            HeartRateListener existing = ref.get();
            if (existing == listener) return; // 已注册
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
        this.currentHR = hr;
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
