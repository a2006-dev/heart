# 心迹 (Heart) ❤️

> 蓝牙心率监测 & 局域网/USB 广播工具

手机连接蓝牙心率设备（胸带、手环），实时显示心率，并通过 WiFi 或 USB 数据线广播到电脑，支持 OBS 直播叠加。

---

## ✨ 功能

### 📱 Android 端
- **蓝牙心率采集** — 连接 Polar、小米手环等 BLE 心率设备
- **心率图表** — MPAndroidChart 实时绘制心率曲线
- **悬浮窗** — 桌面悬浮窗，支持多种样式
- **心率广播** — 通过 HTTP/SSE 将心率推送到局域网
- **USB 数据线直连** — 无需 WiFi，插线即用（配合 PC 客户端）
- **游戏模式** — 心率同步到游戏
- **桌面小部件** — 主屏幕快速查看心率

### 💻 PC 客户端（Go / Python）
- **独立 exe**（~3MB），无需任何运行时
- **浏览器悬浮窗** — 支持 OBS 透明模式叠加
- **USB 直连** — `-usb` 参数自动 ADB forward
- **局域网自动发现** — 自动扫描同一网段设备
- **蓝牙直连电脑** — 跳过手机，PC 直接连心率设备
- **HRV 记录** — 3 分钟以上自动绘制心率变异性曲线

---

## 📦 下载

[GitHub Releases](https://github.com/operit/heart/releases) 页面下载最新 APK 和 PC 客户端。

### Android APK
| 文件 | 说明 |
|------|------|
| `heart-v2.3.apk` | 正式版，已签名可直接安装 |

### PC 客户端
| 文件 | 说明 |
|------|------|
| `heart_client.exe` | Windows exe，双击或命令行运行 |
| `heart_monitor.py` | Python 版（功能更全，带 GUI） |

---

## 🚀 使用

### WiFi 连接（推荐）
1. 手机开「心迹」→ 设置 → 心率广播 → 开启
2. 记下显示的 IP（如 `192.168.1.5:9090`）
3. PC 运行：
   ```bash
   heart_client.exe 192.168.1.5:9090
   ```
4. 浏览器打开 `http://localhost:9091`

### USB 数据线连接
1. 手机 USB 连电脑，开启 USB 调试
2. 手机开「心率广播」
3. PC 运行：
   ```bash
   heart_client.exe -usb
   ```
   或 Python 版点「🔌 USB 直连」

### OBS 叠加
浏览器源添加：
```
http://localhost:9091?transparent=1
```

---

## 🔧 编译

### Android APK
```bash
./gradlew assembleRelease
```

### Go 客户端
```bash
cd pc_client

# Windows
go build -ldflags="-H windowsgui -s -w" -o heart_client.exe

# macOS
go build -ldflags="-s -w" -o heart_client_mac

# Linux
go build -ldflags="-s -w" -o heart_client_linux
```

---

## 📋 更新记录

### v2.3（当前版本）
- ✨ **新增 USB 数据线直连** — PC 客户端支持 `-usb` 参数，Python 版新增「USB 直连」按钮
- 🐛 **修复** — 修复 `HeartEventBus.unregister()` 在 `CopyOnWriteArrayList` 上调用 `iterator.remove()` 导致的偶发闪退
- 🔧 **优化** — 广播页面从 assets 加载，支持自定义 HTML

### v2.2
- 新增游戏模式
- 新增设备特征码自动匹配（广义搜索）
- 优化蓝牙连接稳定性

### v2.1
- 新增 PC 端 Python 脚本
- 新增 HRV 记录功能
- 局域网自动扫描发现设备

### v2.0
- 全新 UI 设计
- 重写蓝牙连接层
- 新增悬浮窗

---

## 🛠 技术栈

| 端 | 技术 |
|---|------|
| Android | Java, AndroidX, MPAndroidChart |
| PC (Go) | Go, net/http, SSE |
| PC (Python) | Python 3, tkinter, bleak, HTTP/SSE |
| 通信 | HTTP REST + SSE (Server-Sent Events) |
| USB | ADB forward (tcp 端口转发) |

---

## 📄 许可证

MIT License
