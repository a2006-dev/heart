package com.xinji.heartbeat.common.event;

/**
 * 基础事件类 — 所有事件都应继承此类
 * 
 * 使用方式：
 * public class MyEvent extends Event {
 *     public String message;
 *     public MyEvent(String message) { this.message = message; }
 * }
 */
public class Event {
    public long timestamp = System.currentTimeMillis();
}
