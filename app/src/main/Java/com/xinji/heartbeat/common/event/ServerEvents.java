package com.xinji.heartbeat.common.event;

/**
 * 广播服务器相关的事件定义
 */
public class ServerEvents {
    /**
     * 服务器启动
     */
    public static class ServerStartedEvent extends Event {
        public String ip;
        public int port;
        
        public ServerStartedEvent(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    /**
     * 服务器停止
     */
    public static class ServerStoppedEvent extends Event {}

    /**
     * 服务器错误
     */
    public static class ServerErrorEvent extends Event {
        public String message;
        
        public ServerErrorEvent(String message) {
            this.message = message;
        }
    }
}
