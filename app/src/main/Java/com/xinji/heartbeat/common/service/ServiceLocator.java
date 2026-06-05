package com.xinji.heartbeat.common.service;

import android.content.Context;

import com.xinji.heartbeat.bluetooth.BleManager;
import com.xinji.heartbeat.server.BroadcastServer;
import com.xinji.heartbeat.widget.FloatWindowManager;
import com.xinji.heartbeat.core.HeartRateManager;
import com.xinji.heartbeat.common.event.EventBus;

/**
 * 服务定位器 — 简单的依赖定位模式
 * 
 * 用途：
 * - 在 Application 或 MainActivity 中初始化所有服务
 * - 其他组件可以通过 ServiceLocator 获取需要的服务
 * - 便于单元测试和依赖注入
 * 
 * 使用示例：
 * 
 * // 初始化（在 MainActivity.onCreate 中）
 * BleManager bleManager = new BleManager(context);
 * ServiceLocator.setService(BleManager.class, bleManager);
 * 
 * // 获取服务（在其他类中）
 * BleManager bleManager = ServiceLocator.getService(BleManager.class);
 */
public class ServiceLocator {
    private static final java.util.Map<Class<?>, Object> services = new java.util.HashMap<>();

    /**
     * 注册服务
     */
    public static <T> void setService(Class<T> serviceClass, T instance) {
        if (serviceClass != null && instance != null) {
            services.put(serviceClass, instance);
        }
    }

    /**
     * 获取服务
     */
    @SuppressWarnings("unchecked")
    public static <T> T getService(Class<T> serviceClass) {
        return (T) services.get(serviceClass);
    }

    /**
     * 移除服务
     */
    public static void removeService(Class<?> serviceClass) {
        services.remove(serviceClass);
    }

    /**
     * 检查服务是否已注册
     */
    public static boolean hasService(Class<?> serviceClass) {
        return services.containsKey(serviceClass);
    }

    /**
     * 清空所有服务
     */
    public static void clear() {
        services.clear();
    }
}
