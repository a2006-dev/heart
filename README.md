<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=0,2,3&height=200&section=header&text=❤️%20心迹&fontSize=80&fontColor=fff&animation=twinkling" width="100%"/>
</p>

<p align="center">
  <img src="https://count.getloli.com/get/@heart?theme=rule34" alt="访客"/>
</p>

<h3 align="center">蓝牙心率监测 · 跨平台广播 · OBS 直播叠加 · MQTT 远程推送</h3>

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
  <img src="https://img.shields.io/github/last-commit/a2006-dev/heart?style=flat-square&logo=git&color=ff5d7c" alt="最后更新"/>
</p>

<br>

<p align="center">
  <a href="#-功能特性">功能特性</a> ·
  <a href="#-快速开始">快速开始</a> ·
  <a href="#-mqtt-远程连接">MQTT 远程</a> ·
  <a href="#-通信协议">通信协议</a> ·
  <a href="#-项目结构">项目结构</a> ·
  <a href="#-构建指南">构建指南</a> ·
  <a href="#-更新日志">更新日志</a>
</p>

<br>

> **心迹** 是一款开源的蓝牙心率监测工具，通过 BLE 连接心率设备，将实时心率展示在手机上，并通过 WiFi / USB / **MQTT** 多种方式推送到电脑。支持桌面悬浮窗、OBS 直播叠加、HRV 记录分析。**v3.0 新增 MQTT 远程推送**，突破局域网限制，随时随地查看心率。

---

## 🔥 功能特性

### 📱 Android

| 功能 | 说明 |
|------|------|
| **蓝牙 BLE 连接** | 支持标准心率服务 (0x180D)，兼容 Polar、小米手环等 |
| **已配对设备常驻** | 已连接过的设备独立列表常驻显示，不因扫描消失，长按可取消配对 |
| **自动重连** | 断开后自动扫描并只连接已知设备，不会连到陌生设备 |
| **实时心率图表** | MPAndroidChart 绘制心率曲线，支持手势交互 |
| **📈 ECG 仿真波形** | P-QRS-T 生理模型仿真，每跳形态各异，基于 RR 间期动态调整 |
| **📊 HRV 实时监测** | SDNN 实时计算，有 RR 数据时为真实值，无 RR 时基于心率估算 |
| **📉 30 秒平均心率** | 滑动窗口计算，稳定显示不跟随瞬时跳变 |
| **悬浮窗** | 5 种样式，支持自定义颜色/字号/透明度，位置自动记忆 |
| **心率广播** | HTTP + SSE 协议推送至局域网，浏览器零配置查看 |
| **📡 MQTT 远程推送** | 跨互联网推送心率，支持公共 Broker / Docker 自建 / 阿里云 / 华为云 |
| **🔗 连接码机制** | 一键生成 `HEARTBEAT#V1#` 格式连接码，电脑端粘贴即连 |
| **自定义 Broker** | 设置页面支持自定义 Broker 地址、端口、Topic，测试连接诊断 |
| **游戏模式** | 自动切换超小悬浮窗，退出后恢复用户样式 |
| **手动 HRV 记录** | 手动启停，≥3 分钟自动保存并绘制 HRV 曲线 |
| **USB 数据线直连** | 无 WiFi 时通过 ADB 端口转发传输数据 |

### 💻 PC

**Go 版** — `heart_client.exe`
- 单文件 ~5.6MB，无需安装任何运行时
- 📡 **MQTT 远程接收** — 粘贴连接码即可接收远程心率
- 浏览器访问 `http://localhost:9091` 查看心率
- OBS 叠加 `http://localhost:9091?transparent=1`
- USB 模式 `-usb` 参数一键直连

**Python 版** — `heart_monitor.py`
- tkinter 图形界面，设备状态一目了然
- 📡 **MQTT 远程接收** — 输入连接码，支持测试连接诊断
- 桌面悬浮窗（7 种样式，可锁定/调字号）
- HRV 实时折线图与曲线分析
- 局域网自动发现设备 + USB 一键连接
- 支持 PC 蓝牙直连（需 bleak 库）

**网页版** — `mqtt.html`
- 纯浏览器 MQTT 接收，零依赖
- 可部署 GitHub Pages，无需任何服务器

---

## 🚀 快速开始

### WiFi 连接（局域网）

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

---

## 📡 MQTT 远程连接

### 手机端操作
```
心迹 → 设置 → MQTT 远程 → 开启 → 复制连接码
```

### PC 端接收（三种方式）

**方式一：** 双击 `heart_client.exe`，粘贴连接码，点「连接」
**方式二：** 运行 `heart_monitor.py`，粘贴连接码
**方式三：** 浏览器打开 `mqtt.html`，粘贴连接码

### 连接码格式

```
HEARTBEAT#V1#GUEMSNDM#broker-cn.emqx.io:1883#heart/rate
```

| 段 | 说明 |
|----|------|
| `HEARTBEAT#V1#` | 固定头 + 协议版本 |
| `GUEMSNDM` | 8 位随机设备标识，区分不同用户 |
| `broker-cn.emqx.io:1883` | MQTT 服务器地址 + 端口 |
| `heart/rate` | 话题名称，实际订阅自动追加设备标识 |

实际接收的话题 = `heart/rate/GUEMSNDM`

### Broker 选择

**🆓 公共免费（推荐测试用）**
| Broker | 端口 | 说明 |
|--------|------|------|
| `broker-cn.emqx.io` | 1883 | EMQX 国内节点，延迟低 ✅ |
| `broker.emqx.io` | 1883 | EMQX 全球集群 |
| `test.mosquitto.org` | 1883 | Eclipse 基金会 |

**🏠 Docker 自建（完全可控）**
```bash
docker run -d --name emqx \
  -p 1883:1883 -p 8083:8083 \
  -p 18083:18083 \
  emqx/emqx:latest
```
管理后台：`http://你的IP:18083`（默认 admin/public）

**☁️ 云平台**
| 平台 | 价格 | 说明 |
|------|------|------|
| 阿里云微消息队列 MQTT | 5元/月起 | 国内延迟最低 |
| 华为云 IoTDA | 100万条/月免费 | 企业级安全 |
| EMQX Cloud | Serverless 免费额度 | 全球节点 |

### 诊断工具

如果收不到数据：
1. 点「测试连接」按钮检测 Broker 是否可达
2. 浏览器打开 `mqtt.html`，粘贴连接码测试
3. 如 mqtt.html 能收到 → exe/py 有问题，重新下载
4. 如收不到 → 检查防火墙或更换 Broker

---

## 📡 通信协议

### 本地广播（HTTP + SSE）

Base URL: `http://<手机IP>:9090`

| 端点 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 广播页面（OBS 透明叠加） |
| `/api/hr` | GET | 返回当前心率 JSON |
| `/api/hr` | POST | 接收心率推送 |
| `/api/sse` | GET | SSE 实时流 |
| `/api/info` | GET | 服务器信息 |

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
| **传输** | MQTT 3.1.1，QoS 0 |
| **数据格式** | `{"hr":72,"device":"设备名","connected":true}` |
| **Topic** | `heart/rate/{设备标识}` |
| **Broker 端口** | TCP 1883 / WebSocket 8083 |

---

## 📁 项目结构

```
Heart/
├── app/                          # Android 模块 (Java)
│   ├── src/main/java/com/xinji/heartbeat/
│   │   ├── MainActivity.java     # 主界面 (ViewPager2 + BottomNav)
│   │   ├── ConnectFragment.java  # 蓝牙扫描与连接
│   │   ├── HomeFragment.java     # 心率首页
│   │   ├── SettingsFragment.java # 设置页（MQTT 配置 + 端口 + 帮助）
│   │   ├── BroadcastActivity.java# 广播配置页（分享 exe/py 二选一）
│   │   ├── mqtt/MqttManager.java # MQTT 推送管理器（零外部依赖）
│   │   ├── server/BroadcastServer.java # HTTP + SSE 服务器
│   │   ├── widget/FloatWindowManager.java # 悬浮窗管理
│   │   └── ...
│   └── src/main/assets/
│       ├── heart_client.exe           # Windows exe (MQTT + 悬浮窗)
│       ├── heart_monitor.py           # Python 全功能版
│       ├── heart_monitor_headless.py  # Python 无头版
│       └── mqtt.html                  # 浏览器 MQTT 接收工具
│
└── pc_client/                    # Go 客户端源码
    ├── main.go                   # Win32 GUI + HTTP 服务
    └── mqtt.go                   # MQTT 协议实现（纯 Go）
```

### 技术栈

| 组件 | 选型 |
|------|------|
| Android | Java, AndroidX, MPAndroidChart |
| PC (Go)  | net/http, Win32 API, 纯 Go MQTT |
| PC (Python) | tkinter, paho-mqtt, bleak |
| 通信 | HTTP REST + SSE + MQTT |
| MQTT Broker | EMQX（公共 / 自建 / 云平台） |

---

## 🔧 构建指南

```bash
# Android APK
./gradlew assembleRelease

# Go 客户端（需 MinGW 交叉编译）
cd pc_client
CC=x86_64-w64-mingw32-gcc CGO_ENABLED=1 GOOS=windows GOARCH=amd64 \
  go build -ldflags="-H windowsgui -s -w" -o heart_client.exe
```

---

## 📋 更新日志

### v3.1 — ECG 仿真波形与 HRV 实时面板（当前版本）

**✨ 新增**
- 📈 **ECG 仿真波形** — P-QRS-T 生理模型，每跳形态各异，收到心率数据时触发一次心跳，中间为平滑基线
- 📊 **HRV 实时面板** — 首页显示 SDNN、RR 间期、30 秒平均心率
- 🔗 **RR-Interval 解析** — BLE 心率特征 `0x2A37` 支持解析 RR 间期，有真实数据时 HRV 为真实值
- 🧩 **EegWaveformBridge** — 解耦的 ECG 数据桥接层，连接 HeartRateManager 与 WebView 前端
- ⚠️ **双重免责声明** — 首页 + 设置页底部 + README
- 💾 **30 秒滑动平均** — 平均心率独立计算，不跟随瞬时跳变

**🔧 优化**
- HRV 算法：基于 RR 历史缓存实时计算 SDNN，数据不足时返回 -1 而非 0
- 波形引擎重构：从固定模板改为连续实时生成
- BleManager 解耦：新接口 `onHeartRateUpdate(int hr, int[] rrIntervals)`，旧接口保留兼容

**🐛 修复**
- 未连接时不再生成假波形，显示待机基线
- 一次蓝牙数据不再触发多次心跳动画

### v3.0 — MQTT 远程推送

**✨ 新增**
- 📡 **MQTT 远程推送** — 跨互联网推送心率，支持公共 Broker / 自建 / 阿里云 / 华为云
- 🔗 **连接码机制** — `HEARTBEAT#V1#` 格式，一键复制，PC 端粘贴即连
- 💻 **三端 MQTT 接收** — Go exe / Python 脚本 / HTML 网页均可接收
- 🐍 **Python 全功能版** — 桌面悬浮窗（7 种样式 + 锁定 + 调字号）、HRV 实时折线图、局域网扫描 + USB 一键连接、MQTT 远程
- 🌐 **mqtt.html** — 纯浏览器 MQTT 接收，可部署 GitHub Pages，零成本
- 🔧 **设置页 MQTT 端口** — 支持自定义 Broker 端口
- 🧩 **MqttManager 解耦** — 零外部依赖，通过回调接口注入心率数据
- 🧩 **BleManager 解耦** — 不依赖 DeviceProfileManager，已配对设备由 UI 层自行查询展示

**🐛 修复**
- exe CONNECT 报文缺协议名长度导致连接失败
- exe 跨线程 UI 操作导致卡顿转圈
- exe 数字刷新残留、悬浮窗不显示数字
- Python 悬浮窗样式变量作用域错误
- 设置页面 `inputType` 编译错误
- **MQTT 精准订阅失效** — PC 端通配符订阅绕过特征码隔离，导致数据串扰；现已全部改为精确订阅，恢复特征码隔离作用
- **手机端占位符冲突** — `deviceTag` 为空时使用 `--------` 占位，存在数据串扰风险；现已改为直接返回基础 Topic
- **新增 10 秒超时检测** — exe/Python/HTML 三端全部支持，链接过期自动提示用户重新获取连接码

**🔧 优化**
- exe 所有 UI 操作投递到主线程，告别卡顿
- MQTT 通配符 `+` 订阅，兼容设备 Tag 变更
- 连接诊断：测试 Broker 可达性 + mqtt.html 快速定位问题

### v2.3
- USB 数据线直连（`-usb` 参数）
- Go 语言 PC 客户端
- ADB 自动端口转发管理

### v2.2
- 游戏模式、设备特征码自动匹配、蓝牙稳定性优化

### v2.1
- Python 客户端、HRV 记录、局域网自动发现

### v2.0
- UI 重构、蓝牙层重写、悬浮窗系统

---

## ⚠️ 免责声明

本应用**仅作娱乐和参考用途**，**不可作为医疗诊断依据**。

- 所有心率数据来源于蓝牙穿戴设备，仅供参考
- ECG 波形基于心率数据与数学模型仿真生成，并非真实心电图信号
- HRV 数据在有 RR 间期输入时为真实计算值，无 RR 数据时为估算值
- 如有身体不适，请及时就医，不要依赖本应用做任何医疗决策

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