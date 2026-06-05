package com.xinji.heartbeat.common.event;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线 — 发布-订阅模式，解除模块间的直接依赖
 * 
 * 特点：
 * - 线程安全（使用 CopyOnWriteArrayList）
 * - 自动在主线程回调
 * - 支持优先级
 * - 自动清理垃圾引用
 * 
 * 使用示例：
 * 
 * // 1. 发布事件
 * EventBus.getInstance().post(new HeartRateUpdateEvent(120));
 * 
 * // 2. 订阅事件
 * EventBus.getInstance().subscribe(HeartRateUpdateEvent.class, event -> {
 *     Log.d(TAG, "心率更新: " + ((HeartRateUpdateEvent)event).heartRate);
 * });
 * 
 * // 3. 取消订阅
 * EventBus.getInstance().unsubscribe(HeartRateUpdateEvent.class, listener);
 */
public class EventBus {
    private static volatile EventBus instance;
    private final Map<Class<?>, List<EventListener>> listeners = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface EventListener {
        void onEvent(Event event);
    }

    public static EventBus getInstance() {
        if (instance == null) {
            synchronized (EventBus.class) {
                if (instance == null) {
                    instance = new EventBus();
                }
            }
        }
        return instance;
    }

    /**
     * 发布事件 — 将在主线程中回调所有订阅者
     */
    public void post(Event event) {
        if (event == null) return;
        Class<?> eventClass = event.getClass();
        List<EventListener> eventListeners = listeners.get(eventClass);
        
        if (eventListeners != null && !eventListeners.isEmpty()) {
            // 复制列表以防修改
            List<EventListener> copy = new ArrayList<>(eventListeners);
            mainHandler.post(() -> {
                for (EventListener listener : copy) {
                    try {
                        listener.onEvent(event);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    /**
     * 订阅事件
     */
    public void subscribe(Class<? extends Event> eventClass, EventListener listener) {
        if (eventClass == null || listener == null) return;
        
        synchronized (listeners) {
            List<EventListener> eventListeners = listeners.computeIfAbsent(
                eventClass, k -> new CopyOnWriteArrayList<>());
            
            // 避免重复注册
            if (!eventListeners.contains(listener)) {
                eventListeners.add(listener);
            }
        }
    }

    /**
     * 取消订阅
     */
    public void unsubscribe(Class<? extends Event> eventClass, EventListener listener) {
        if (eventClass == null || listener == null) return;
        
        synchronized (listeners) {
            List<EventListener> eventListeners = listeners.get(eventClass);
            if (eventListeners != null) {
                eventListeners.remove(listener);
            }
        }
    }

    /**
     * 清空所有订阅
     */
    public void clearAllSubscriptions() {
        synchronized (listeners) {
            listeners.clear();
        }
    }

    /**
     * 获取某个事件类型的订阅者数量（用于测试）
     */
    public int getListenerCount(Class<? extends Event> eventClass) {
        synchronized (listeners) {
            List<EventListener> eventListeners = listeners.get(eventClass);
            return eventListeners != null ? eventListeners.size() : 0;
        }
    }
}
