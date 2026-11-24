# 心迹 PC 客户端 - 编译指南

## 📦 前置依赖

- Go 1.21+
- **Windows 编译环境**：MinGW-w64（提供 `gcc`），因为窗口界面是内嵌的 Win32 C 代码，需要 cgo
- macOS / Linux 无法编译此客户端（代码直接调用了 `windows.h`）

Windows 上推荐直接装 [msys2](https://www.msys2.org/)，然后在 UCRT64 终端里装工具链：

```bash
pacman -S mingw-w64-ucrt-x86_64-gcc
```

编译前确认 `gcc` 可用：

```bash
gcc --version
```

## 🪟 编译

### Windows exe（无控制台窗口）

```bash
cd pc_client
set CGO_ENABLED=1
go build -ldflags="-H windowsgui -s -w" -o heart_client.exe
```

- 双击运行 → 弹出窗口，无控制台黑框
- 界面是 Win32 原生控件，不依赖 WebView2 或任何外部运行时

### 保留控制台版（调试用）

```bash
cd pc_client
go build -ldflags="-s -w" -o heart_client_debug.exe
```

调试版会保留一个控制台窗口，方便看 panic 和日志输出。

## 📋 使用方法

### WiFi 模式

1. 手机打开「心迹」→ 设置 → 心率广播 → 开启
2. 双击 `heart_client.exe`，弹窗打开后自动连接
3. 或指定 IP：`heart_client.exe 192.168.1.5:9090`

### USB 模式

```bash
heart_client.exe -usb
```

需要事先用 `adb forward` 把手机的 9090 端口映射到本机。

### OBS 叠加

```
OBS → 来源 → + → 浏览器
URL: http://localhost:9091/?transparent=1
```

## 📝 注意事项

- 程序内置一个本地 HTTP 服务（默认 9091，被占用时自动往后找空闲端口），用于给 OBS 提供叠加页面
- 「手机推送地址」和「OBS 叠加」两行显示的 IP 由程序启动时自动探测
- MQTT 远程模式在主界面粘贴手机端生成的连接码即可，无需手动填 Broker

## 📁 源码结构

```
pc_client/
├── main.go      # 主程序 + 内嵌 Win32 窗口代码（cgo）
├── mqtt.go      # 纯 Go 实现的极简 MQTT 客户端（QoS 0）
├── go.mod
└── BUILD.md
```