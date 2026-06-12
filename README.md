# 心迹 (Heart) ❤️

> 蓝牙心率监测 & 局域网/USB 广播工具  
> 连接蓝牙心率设备（胸带、手环），实时显示心率，通过 WiFi 或 USB 数据线广播到电脑，支持 OBS 直播叠加。

**版本：v2.3** | minSdk 24 / targetSdk 34 | MIT License

---

## 功能特性

### 📱 Android 端
- **蓝牙 BLE 连接** — 支持标准心率服务（0x180D），自动重连，记忆上次设备
- **心率图表** — MPAndroidChart 实时绘制心率曲线
- **悬浮窗** — 5 种样式（默认/暗夜/烈焰/冰雪/透明），自由拖拽，位置自动记忆
- **心率广播** — 通过 HTTP/SSE 将心率推送到局域网，浏览器实时查看
- **USB 数据线直连** — 无需 WiFi，插线即用（配合 PC 客户端）
- **游戏模式** — 超小悬浮窗，不遮挡游戏画面
- **桌面小部件** — 主屏幕快速查看心率
- **手动记录** — 3 分钟以上自动保存，含心率范围、平均值、迷你折线图

### 💻 PC 客户端
| 客户端 | 说明 |
|--------|------|
| **Go 版** (`heart_client.exe`) | 独立 exe（~3MB），无需任何运行时，浏览器显示悬浮窗 |
| **Python 版** (`heart_monitor.py`) | 功能更全，带 GUI 界面，支持蓝牙直连、HRV 记录 |

---

## 🚀 快速开始

### WiFi 连接
1. 手机开「心迹」→ 底部「设置」→ 开启「心率广播」
2. 记下显示的 IP（如 `192.168.1.5:9090`）
3. PC 运行：
   ```bash
   heart_client.exe 192.168.1.5:9090
   ```
4. 浏览器打开 `http://localhost:9091` 查看心率悬浮窗

### USB 数据线连接（无需 WiFi）
1. 手机 USB 连电脑，开启 **USB 调试**
2. 手机开「心率广播」
3. PC 运行：
   ```bash
   heart_client.exe -usb    # Go 版
   ```
   或 Python 版点 **「🔌 USB 直连」** 按钮

### OBS 直播叠加
浏览器源添加：
```
http://localhost:9091?transparent=1
```

---

## ⚠️ 与远程仓库 main 分支的差异

本地上传的代码（v2.3）与 GitHub 远程 `main` 分支（v3.1）是**两条独立开发线**，主要差异如下：

| 对比项 | 远程 main 分支 (v3.1) | 本地上传 (v2.3) |
|--------|----------------------|-----------------|
| **代码架构** | 重构版，移除 HeartServiceLocator/HeartEventBus，直接在 MainActivity 中初始化管理器 | 使用 HeartServiceLocator 服务定位器 + HeartEventBus 事件总线，模块间解耦更彻底 |
| **目录结构** | `app/src/main/Java/`（大写 J） | `app/src/main/java/`（小写 j，标准 Android 结构） |
| **PC 客户端** | 仅 Python 脚本 | Go 独立 exe + Python 脚本双端支持 |
| **USB 直连** | ❌ 不支持 | ✅ 支持（`-usb` 参数 / 「USB 直连」按钮） |
| **adb forward** | ❌ 不支持 | ✅ 自动执行 `adb forward` 端口转发 |
| **崩溃修复** | 未处理 | ✅ 修复 `HeartEventBus.unregister()` 在 CopyOnWriteArrayList 上调用 `iterator.remove()` 导致的崩溃 |

### 本地上传 v2.3 的增量改进

#### ✨ 新增
- **USB 数据线直连** — Go 客户端 `-usb` 参数，Python 版「USB 直连」按钮
- **Go 语言 PC 客户端** — 独立 exe，跨平台编译（Windows/macOS/Linux）
- **ADB forward 自动管理** — 一键连接，断开时自动清理端口转发

#### 🐛 修复
- **`HeartEventBus.unregister()` 崩溃** — `CopyOnWriteArrayList` 的 `iterator.remove()` 改为 `listeners.remove(ref)`，避免 `UnsupportedOperationException`

#### ♻️ 未变动的部分
- 所有蓝牙连接、心率采集、悬浮窗、图表、手动记录等核心功能与远程一致
- UI、布局、资源文件保持一致
- 版本号仍为 v2.3（与远程 build.gradle 一致）

---

## 📦 下载

从 [Releases](https://github.com/a2006-dev/heart/releases) 页面下载最新 APK。

### Android APK
| 文件 | 大小 | 说明 |
|------|------|------|
| `Heart-v2.3.apk` | ~2.3MB | 已签名，可直接安装 |

### PC 客户端
| 文件 | 说明 |
|------|------|
| `heart_client.exe` | Windows 独立 exe，`-usb` 参数 USB 直连 |
| `heart_monitor.py` | Python 版（带 GUI，支持蓝牙直连） |

---

## 🔧 编译

### Android APK
```bash
./gradlew assembleRelease
```
签名后的 APK 位于 `app/build/outputs/apk/release/`

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
- ✨ **新增 USB 数据线直连** — PC 客户端支持 `-usb` 参数自动 ADB forward
- ✨ **新增 Go 语言 PC 客户端** — 独立 exe（~3MB），跨平台，无需运行时
- 🐛 **修复** — `HeartEventBus.unregister()` 在 `CopyOnWriteArrayList` 上调用 `iterator.remove()` 导致的偶发闪退
- 🔧 **优化** — PC 端支持 OBS 透明模式（`?transparent=1`）

### v2.2
- 新增游戏模式
- 新增设备特征码自动匹配（广义搜索）
- 优化蓝牙连接稳定性

### v2.1
- 新增 PC 端 Python 脚本
- 新增 HRV 记录功能

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
| PC (Python) | Python 3, tkinter, bleak |
| 通信 | HTTP REST + SSE (Server-Sent Events) |
| USB 直连 | ADB forward (tcp 端口转发) |

---

## 📄 许可证

MIT License