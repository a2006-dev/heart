<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=0,2,3&height=200&section=header&text=❤️%20心迹&fontSize=80&fontColor=fff&animation=twinkling" width="100%"/>
</p>

<p align="center">
  <img src="https://count.getloli.com/get/@heart?theme=rule34" alt="访客"/>
</p>

<h3 align="center">蓝牙心率监测 · 跨平台广播 · OBS 直播叠加</h3>

<p align="center">
  <a href="https://github.com/a2006-dev/heart/releases">
    <img src="https://img.shields.io/github/v/release/a2006-dev/heart?style=flat-square&logo=github&color=ff5d7c" alt="最新版本"/>
  </a>
  <a href="https://github.com/a2006-dev/heart/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/a2006-dev/heart?style=flat-square&logo=open-source-initiative&color=blue" alt="许可证"/>
  </a>
  <a href="https://github.com/a2006-dev/heart">
    <img src="https://img.shields.io/github/languages/top/a2006-dev/heart?style=flat-square&logo=java&color=orange" alt="语言"/>
  </a>
</p>

<br>

<p align="center">
  <a href="#-功能特性">功能特性</a> ·
  <a href="#-快速开始">快速开始</a> ·
  <a href="#-通信协议">通信协议</a> ·
  <a href="#-项目结构">项目结构</a> ·
  <a href="#-构建指南">构建指南</a> ·
  <a href="#-更新日志">更新日志</a>
</p>

<br>

> **心迹** 是一款开源的蓝牙心率监测工具，通过 BLE 连接心率设备，将实时心率广播至局域网 PC 端，支持浏览器悬浮窗与 OBS 直播叠加。**v3.0 新增 MQTT 远程推送**，突破局域网限制，随时随地查看心率。无需安装任何 PC 端运行时，下载即用。

---

## 🔥 功能特性

### 📱 Android

| 功能 | 说明 |
|------|------|
| **蓝牙 BLE 连接** | 支持标准心率服务 (0x180D)，兼容 Polar、小米手环等 |
| **自动重连** | 断开后自动扫描重连，连接状态持久化 |
| **实时心率图表** | MPAndroidChart 绘制心率曲线，支持手势交互 |
| **悬浮窗** | 5 种样式，支持自定义颜色/字号/透明度，位置自动记忆 |
| **心率广播** | HTTP + SSE 协议推送至局域网，浏览器零配置查看 |
| **游戏模式** | 自动切换超小悬浮窗，退出后恢复用户样式 |
| **手动 HRV 记录** | 手动启停，≥3 分钟自动保存并绘制 HRV 曲线 |
| **USB 数据线直连** | 无 WiFi 时通过 ADB 端口转发传输数据 |
| **📡 MQTT 远程推送** | 跨互联网推送心率，支持公共/自建 Broker |

### 💻 PC

**Go 版** — `heart_client.exe`
- 单文件 ~5.6MB，无需安装任何运行时
- 浏览器访问 `http://localhost:9091` 查看心率
- 🔗 **MQTT 远程接收** — 粘贴连接码即可接收远程心率
- OBS 叠加 `http://localhost:9091?transparent=1`
- USB 模式 `-usb` 参数一键直连
- 跨平台编译（Windows / macOS / Linux）

**Python 版** — `heart_monitor.py`
- tkinter 图形界面，设备状态一目了然
- 🔗 **MQTT 远程接收** — 输入连接码，支持测试连接诊断
- 支持 PC 蓝牙直连（需 bleak 库）
- 局域网自动发现设备 + USB 一键连接
- 桌面悬浮窗（7 种样式，可锁定/调字号）
- HRV 记录与曲线分析

---

## 🚀 快速开始

### WiFi 连接

```bash
# 1. 手机开启广播
心迹 → 设置 → 开启「心率广播」

# 2. PC 连接
heart_client.exe 192.168.1.5:9090

# 3. 浏览器查看
http://localhost:9091
```

### USB 直连

```bash
# 1. 手机插线，开启 USB 调试
# 2. 手机开心率广播
# 3. PC 运行（需 adb.exe 在同目录）
heart_client.exe -usb
```

### OBS 叠加

```
OBS → 来源 → + → 浏览器
URL: http://localhost:9091?transparent=1
宽度: 200 · 高度: 120
```

### 📡 MQTT 远程连接（跨互联网）

```
# 1. 手机开启 MQTT 远程
心迹 → 设置 → MQTT 远程 → 开启 → 复制连接码

# 2. PC 接收
# 方式一：双击 heart_client.exe，粘贴连接码，点「连接」
# 方式二：运行 heart_monitor.py，粘贴连接码
# 方式三：浏览器打开 mqtt.html，粘贴连接码

# 连接码格式
HEARTBEAT#V1#设备标识#broker地址:端口#话题
```

**公共 Broker（免费，无需注册）：**
- `broker-cn.emqx.io:1883`（国内，延迟低）
- `broker.emqx.io:1883`（全球集群）

**自建 Broker：**
```bash
docker run -d --name emqx -p 1883:1883 -p 18083:18083 emqx/emqx:latest
```

**云平台：**
- 阿里云微消息队列 MQTT：5元/月
- 华为云 IoTDA：100万条/月免费

---

## 📡 通信协议

### 本地广播（HTTP + SSE）
Base URL: `http://<手机IP>:9090`

| 端点 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 广播页面 |
| `/api/hr` | GET | 返回当前心率 JSON |
| `/api/hr` | POST | 接收心率推送 |
| `/api/sse` | GET | SSE 实时流 |

```json
// GET /api/hr 响应
{
  "hr": 72,
  "device": "Polar H10",
  "connected": true
}
```

| 端口 | 用途 |
|------|------|
| 9090 | 手机广播服务 |
| 9091 | PC 本地服务 / ADB 转发 |

### MQTT 远程

| 项 | 说明 |
|------|------|
| **连接码格式** | `HEARTBEAT#V1#设备标识#host:port#topic` |
| **默认 Topic** | `heart/rate`，自动追加设备标识如 `heart/rate/ABC123` |
| **数据格式** | `{"hr":72,"device":"Polar H10","connected":true}` |
| **Broker 端口** | TCP 1883，WebSocket 8083 |
| **QoS** | 0（至多一次） |

**连接码示例：**
```
HEARTBEAT#V1#X3K7M9N2#broker-cn.emqx.io:1883#heart/rate
```

---

## 📁 项目结构

```
Heart/
├── app/                          # Android 模块 (Java)
│   ├── src/main/java/com/xinji/heartbeat/
│   │   ├── MainActivity.java     # 主界面 (ViewPager2 + BottomNav)
│   │   ├── ConnectFragment.java  # 蓝牙扫描与连接
│   │   ├── HomeFragment.java     # 心率首页
│   │   ├── SettingsFragment.java # 设置页
│   │   ├── BroadcastActivity.java# 广播配置页
│   │   ├── app/
│   │   │   ├── HeartEventBus.java         # 事件总线（模块解耦）
│   │   │   └── HeartServiceLocator.java   # 服务定位器
│   │   ├── bluetooth/BleManager.java      # BLE 管理
│   │   ├── server/BroadcastServer.java    # HTTP + SSE 服务器
│   │   ├── widget/FloatWindowManager.java # 悬浮窗管理
│   │   └── core/...
│   ├── mqtt/MqttManager.java     # MQTT 推送管理器（TCP 直连，零依赖）
│   └── src/main/assets/
│       ├── heart_monitor.py           # Python 客户端（MQTT + 悬浮窗 + HRV）
│       ├── heart_monitor_headless.py  # Python 无头版
│       ├── heart_client.exe           # Go 编译的 Windows exe
│       └── mqtt.html                  # 浏览器 MQTT 接收工具
│
└── pc_client/                    # Go 客户端源码
    ├── main.go                   # Win32 GUI + HTTP 服务
    └── mqtt.go                   # MQTT 协议实现（纯 Go，无外部依赖）
```

### 技术栈

| 组件 | 选型 |
|------|------|
| Android | Java, AndroidX, MPAndroidChart |
| PC (Go)  | net/http, Win32 API, 纯 Go MQTT |
| PC (Python) | tkinter, paho-mqtt, bleak |
| 通信 | HTTP REST + SSE + **MQTT** |
| MQTT Broker | EMQX（公共/自建/阿里云/华为云） |

---

## 🔧 构建指南

```bash
# Android APK
./gradlew assembleRelease

# Go 客户端
cd pc_client
GOOS=windows GOARCH=amd64 go build -ldflags="-s -w" -o heart_client.exe
GOOS=darwin GOARCH=amd64 go build -ldflags="-s -w" -o heart_client_mac
GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o heart_client_linux
```

---

## 📋 更新日志

### v3.0 — MQTT 远程推送

**✨ 新增**
- 📡 **MQTT 远程推送** — 手机端通过 MQTT 跨互联网推送心率，支持公共/自建/云平台 Broker
- 🔗 **连接码机制** — 一键生成 `HEARTBEAT#V1#` 格式连接码，PC 端粘贴即连
- 💻 **Go 客户端增强** — 新增 MQTT 接收、Win32 悬浮窗、测试连接诊断
- 🐍 **Python 客户端重写** — 悬浮窗（7种样式+锁定+调字号）、HRV 实时折线图、局域网扫描+USB、MQTT 接收
- 🌐 **mqtt.html** — 纯浏览器 MQTT 接收工具，可部署 GitHub Pages
- 🧩 **MqttManager 解耦** — 零外部依赖，通过回调接口注入心率数据

**🐛 修复**
- exe CONNECT 报文缺协议名长度导致连接失败
- exe 跨线程 UI 操作导致卡顿转圈
- exe 数字刷新残留、悬浮窗不显示数字
- Python GUI 滚动条 / 窗口尺寸适配
- 悬浮窗样式变量作用域错误
- 设置页面 `inputType` 编译错误

**🔧 优化**
- exe 所有 UI 操作投递到主线程执行
- MQTT 支持通配符 `+` 订阅
- 连接诊断：测试 Broker 可达性 + mqtt.html 定位问题
- 设置页面 MQTT 配置区增加端口输入

### v2.3

**✨ 新增**
- USB 数据线直连（`-usb` 参数 /  USB 按钮）
- Go 语言 PC 客户端（独立 exe，跨平台编译）
- ADB 自动管理（连接时转发，退出时清理）

**🐛 修复**
- HeartEventBus 崩溃（CopyOnWriteArrayList 迭代器 remove 问题）

**🔧 优化**
- 悬浮窗默认不透明
- 启动时优先检测本地 127.0.0.1:9090

### v2.2

- 游戏模式、设备特征码自动匹配、蓝牙稳定性优化

### v2.1

- Python 客户端、HRV 记录、局域网自动发现

### v2.0

- UI 重构、蓝牙层重写、悬浮窗系统

---

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=a2006-dev/heart&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=a2006-dev/heart&type=Date" />
    <img alt="Star History" src="https://api.star-history.com/svg?repos=a2006-dev/heart&type=Date" width="600"/>
  </picture>
  <br><br>
  <sub>MIT License · Copyright © 2026 a2006-dev</sub>
</p>

<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=0,2,3&height=120&section=footer" width="100%"/>
</p>