# 心迹 (Heart) ❤️
> 蓝牙心率监测 & 跨平台广播工具  
> 连接蓝牙心率设备（胸带、手环），实时显示心率，通过 WiFi/USB/MQTT 多种方式推送到电脑，支持 OBS 直播叠加。
**版本：v3.0** | minSdk 24 / targetSdk 34 | MIT License

---

## 功能特性

### 📱 Android 端
- **蓝牙 BLE 连接** — 支持标准心率服务（0x180D），自动重连，记忆上次设备
- **心率图表** — MPAndroidChart 实时绘制心率曲线
- **悬浮窗** — 5 种样式（默认/暗夜/烈焰/冰雪/透明），自由拖拽，位置自动记忆
- **心率广播** — 通过 HTTP/SSE 将心率推送到局域网，浏览器实时查看
- **USB 数据线直连** — 无需 WiFi，插线即用（配合 PC 客户端）
- **📡 MQTT 远程推送** — 跨互联网推送心率，支持公共 Broker / 自建 / 阿里云 / 华为云
- **连接码机制** — 一键生成 `HEARTBEAT#V1#` 格式连接码，电脑端粘贴即连
- **自定义 Broker** — 设置页面支持自定义 Broker 地址、端口、Topic
- **游戏模式** — 超小悬浮窗，不遮挡游戏画面
- **手动记录** — 3 分钟以上自动保存，含心率范围、平均值、迷你折线图

### 💻 PC 客户端

| 客户端 | 说明 |
|--------|------|
| **Go 版** (`heart_client.exe`) | 独立 exe（~5.6MB），无需任何运行时，支持 MQTT 远程接收 |
| **Python 版** (`heart_monitor.py`) | 全功能版，支持 MQTT、桌面悬浮窗（7样式）、HRV、局域网+USB |
| **网页版** (`mqtt.html`) | 纯浏览器 MQTT 接收，可部署 GitHub Pages |

---

## 🚀 快速开始

### WiFi 连接（局域网）
1. 手机开「心迹」→ 底部「设置」→ 开启「心率广播」
2. 记下显示的 IP（如 `192.168.1.5:9090`）
3. PC 运行：
   ```bash
   heart_client.exe 192.168.1.5:9090
   ```
4. 浏览器打开 `http://localhost:9091` 查看心率悬浮窗

### 📡 MQTT 远程连接（跨互联网）
1. 手机开「心迹」→ 设置 → MQTT 远程 → 开启 → 复制连接码
2. PC 端粘贴连接码即可接收（支持 exe / Python / HTML 三种方式）

**连接码格式：**
```
HEARTBEAT#V1#设备标识#broker地址:端口#话题
```

**公共 Broker（免费，无需注册）：**
- `broker-cn.emqx.io:1883`（国内节点，延迟低 ✅）
- `broker.emqx.io:1883`（全球集群）

**自建 Broker：**
```bash
docker run -d --name emqx -p 1883:1883 -p 8083:8083 -p 18083:18083 emqx/emqx:latest
```

**云平台：**
- 阿里云微消息队列 MQTT：5元/月
- 华为云 IoTDA：100万条/月免费

### USB 数据线连接（无需 WiFi）
1. 手机 USB 连电脑，开启 **USB 调试**
2. 手机开「心率广播」
3. PC 运行：
   ```bash
   heart_client.exe -usb    # Go 版
   ```
   或 Python 版点 **「USB 直连」** 按钮

### OBS 直播叠加
```
OBS → 来源 → + → 浏览器
URL: http://localhost:9091?transparent=1
```

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

| 端口 | 用途 |
|------|------|
| 9090 | 手机广播服务 |
| 9091 | PC 本地服务 / ADB 转发 |

### 📡 MQTT 远程协议

| 项 | 说明 |
|------|------|
| **连接码格式** | `HEARTBEAT#V1#设备标识#host:port#topic` |
| **默认 Topic** | `heart/rate`，自动追加设备标识如 `heart/rate/ABC123` |
| **数据格式** | `{"hr":72,"device":"设备名","connected":true}` |
| **Broker 端口** | TCP 1883，WebSocket 8083 |
| **QoS** | 0（至多一次） |

**连接码示例：**
```
HEARTBEAT#V1#X3K7M9N2#broker-cn.emqx.io:1883#heart/rate
```

---

## 📦 下载

从 [Releases](https://github.com/a2006-dev/heart/releases) 页面下载最新版本。

### Android APK
| 文件 | 大小 | 说明 |
|------|------|------|
| `heart_v3.0.apk` | ~4.8MB | 已签名，可直接安装 |

### PC 客户端
| 文件 | 说明 |
|------|------|
| `heart_client_v3.0.exe` | Windows exe，支持 MQTT 远程接收 + 局域网 + USB |
| `heart_monitor.py` | Python 全功能版（MQTT + 悬浮窗 + HRV + 局域网 + USB） |
| `heart_monitor_headless.py` | Python 无头版（无 GUI，浏览器操作） |
| `mqtt.html` | 浏览器 MQTT 接收工具，可放 GitHub Pages |

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
CC=x86_64-w64-mingw32-gcc CGO_ENABLED=1 GOOS=windows GOARCH=amd64 go build -ldflags="-H windowsgui -s -w" -o heart_client.exe
```

---

## 📋 更新记录

### v3.0 — MQTT 远程推送

**✨ 新增**
- 📡 **MQTT 远程推送** — 跨互联网推送心率，支持公共 Broker / Docker 自建 / 阿里云 / 华为云
- 🔗 **连接码机制** — 手机端一键生成 `HEARTBEAT#V1#` 连接码，PC 端粘贴即连
- 💻 **三端 MQTT 接收** — Go exe / Python / HTML 浏览器均可接收
- 🐍 **Python 全功能版** — 桌面悬浮窗（7种样式+锁定+调字号）、HRV 实时折线图、局域网扫描+USB 一键连接、MQTT 远程
- 🌐 **mqtt.html** — 纯浏览器 MQTT 接收工具，零依赖，可部署 GitHub Pages
- 🔧 **设置页 MQTT 端口** — 支持自定义 Broker 端口
- 🧩 **MqttManager 解耦** — 零外部依赖，通过回调接口注入心率数据

**🐛 修复**
- exe CONNECT 报文缺协议名长度导致连接失败
- exe 跨线程 UI 操作导致卡顿转圈
- exe 数字刷新残留、悬浮窗不显示数字
- Python 悬浮窗样式变量作用域错误
- 设置页面 `inputType` 编译错误

**🔧 优化**
- exe 所有 UI 操作投递到主线程，告别卡顿
- MQTT 通配符 `+` 订阅，兼容设备 Tag 变更
- 连接诊断：测试 Broker 可达性 + mqtt.html 定位问题

### v2.3
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
| PC (Go) | Go, Win32 API, net/http, 纯 Go MQTT 实现 |
| PC (Python) | Python 3, tkinter, paho-mqtt, bleak |
| 通信 | HTTP REST + SSE + **MQTT** |
| MQTT Broker | EMQX（公共/自建/阿里云/华为云） |
| USB 直连 | ADB forward (tcp 端口转发) |

---

## 📄 许可证
MIT License