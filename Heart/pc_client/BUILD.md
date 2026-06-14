# 心迹 PC 客户端 - 编译指南（v3.0 弹窗版）

## 📦 前置依赖

Go 1.21+，安装 WebView 依赖：

```bash
go mod tidy
```

> Windows 上 WebView2 运行时已预装在 Win10/11 中，无需额外安装。
> macOS/Linux 会自动使用系统 WebView（GTK/WKWebView）。

## 🪟 编译弹窗版（默认无控制台）

### Windows exe（推荐）
```bash
cd pc_client
go build -ldflags="-H windowsgui -s -w" -o heart_client.exe
```
- ✅ 双击运行 → 弹出窗口，无控制台黑框
- ✅ exe 体积约 5MB（UPX 压缩后约 2MB）

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

## 🖥️ 保留控制台版（调试用）
```bash
go build -ldflags="-s -w" -o heart_client_debug.exe
```

## 📋 使用方法

### WiFi 模式
1. 手机打开「心迹」→ 设置 → 心率广播 → 开启
2. 双击 `heart_client.exe`，弹窗打开后自动连接
3. 或指定 IP：`heart_client.exe 192.168.1.5:9090`

### USB 模式
```bash
heart_client.exe -usb
```

### OBS 叠加
```
OBS → 来源 → + → 浏览器
URL: http://localhost:9091/?transparent=1
```

## 📝 注意事项
- 弹窗版使用 WebView2，Win10/11 自带
- 如需旧版控制台版，切换到 git tag v2.3
- 弹窗内的「关闭窗口」链接可退出程序