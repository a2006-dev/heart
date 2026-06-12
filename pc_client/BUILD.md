# 心迹 PC 客户端 - 编译指南

## 快速编译（需要安装 Go）

### Windows exe
```bash
cd pc_client
go build -ldflags="-H windowsgui -s -w" -o heart_client.exe
```

### macOS
```bash
cd pc_client
go build -ldflags="-s -w" -o heart_client_mac
```

### Linux
```bash
cd pc_client
go build -ldflags="-s -w" -o heart_client_linux
```

## 交叉编译（在 Windows 上编译其他平台）
```bash
# Windows exe
set GOOS=windows&& set GOARCH=amd64&& go build -ldflags="-H windowsgui -s -w" -o heart_client.exe

# macOS
set GOOS=darwin&& set GOARCH=amd64&& go build -ldflags="-s -w" -o heart_client_mac

# Linux
set GOOS=linux&& set GOARCH=amd64&& go build -ldflags="-s -w" -o heart_client_linux
```

## 一键编译脚本（Windows）
创建 `build.bat`:
```batch
@echo off
echo 正在编译心迹 PC 客户端...
go build -ldflags="-H windowsgui -s -w" -o heart_client.exe
echo 编译完成: heart_client.exe
pause
```

## 使用方法
### WiFi 模式
1. 手机打开「心迹」→ 设置 → 心率广播 → 开启
2. 记下显示的 IP 地址（如 192.168.1.5:9090）
3. PC 上运行:
   ```
   heart_client.exe 192.168.1.5:9090
   ```
4. 浏览器自动打开 http://localhost:9091 显示心率悬浮窗
5. 支持 OBS 透明模式加 `?transparent=1`

### USB 模式（不需要 WiFi）
1. 手机用 USB 线连接电脑
2. 手机开启「USB 调试」
3. 手机打开「心迹」→ 设置 → 心率广播 → 开启
4. PC 上运行:
   ```
   heart_client.exe -usb
   ```
   程序会自动执行 `adb forward` 并连接手机
5. 浏览器打开 http://localhost:9091 显示心率悬浮窗

## 体积说明
- 编译后 exe 约 3MB
- UPX 压缩后约 1.5MB
- 完全独立运行，不需要 .NET / Python / Java 等任何运行时