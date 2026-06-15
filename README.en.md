<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=0,2,3&height=200&section=header&text=❤️%20HeartBeat&fontSize=80&fontColor=fff&animation=twinkling" width="100%"/>
</p>

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/lang-中文-ff5d7c?style=flat-square" alt="中文"/></a>
  <a href="README.en.md"><img src="https://img.shields.io/badge/language-English-blue?style=flat-square" alt="English"/></a>
</p>

<p align="center">
  <img src="https://count.getloli.com/get/@heart?theme=rule34" alt="visitors"/>
</p>

<h3 align="center">Bluetooth Heart Rate Monitor · Multi-platform Broadcasting · OBS Live Overlay · MQTT Remote Push</h3>

<p align="center">
  <a href="https://github.com/a2006-dev/heart/releases">
    <img src="https://img.shields.io/github/v/release/a2006-dev/heart?style=flat-square&logo=github&color=ff5d7c" alt="Latest Release"/>
  </a>
  <a href="https://github.com/a2006-dev/heart/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/a2006-dev/heart?style=flat-square&logo=open-source-initiative&color=blue" alt="License"/>
  </a>
  <a href="https://github.com/a2006-dev/heart">
    <img src="https://img.shields.io/github/languages/top/a2006-dev/heart?style=flat-square&logo=java&color=orange" alt="Language"/>
  </a>
  <img src="https://img.shields.io/github/last-commit/a2006-dev/heart?style=flat-square&logo=git&color=ff5d7c" alt="Last Commit"/>
</p>

<br>

<p align="center">
  <a href="#-features">Features</a> ·
  <a href="#-quick-start">Quick Start</a> ·
  <a href="#-mqtt-remote-connection">MQTT Remote</a> ·
  <a href="#-communication-protocol">Protocol</a> ·
  <a href="#-project-structure">Structure</a> ·
  <a href="#-build-guide">Build</a> ·
  <a href="#-changelog">Changelog</a>
</p>

<br>

> **HeartBeat** is an open-source Bluetooth heart rate monitor. It connects to BLE heart rate devices, displays real-time HR on your phone, and pushes data to PC via WiFi / USB / **MQTT**. Supports floating windows, OBS live overlay, and HRV analysis. **v3.1 introduces a redesigned onboarding & clearer permission requests**.

---

## 🔥 Features

### 📱 Android

| Feature | Description |
|---------|-------------|
| **Onboarding 🚀** | First-launch guide with step-by-step feature showcase & permission requests |
| **BLE Connection** | Standard HR service (0x180D), compatible with Polar, Mi Band, etc. |
| **Paired Devices** | Connected devices persist in a separate list, long-press to unpair |
| **Auto Reconnect** | Scans and reconnects only known devices automatically |
| **Real-time Chart** | HR curve via MPAndroidChart with gesture interaction |
| **Floating Window** | 5 styles, customizable color/font/opacity, remembers position |
| **HR Broadcast** | HTTP + SSE push to LAN, zero-config browser access |
| **📡 MQTT Remote** | Cross-internet HR push, supports public/Docker/cloud brokers |
| **🔗 Connection Code** | One-click `HEARTBEAT#V1#` code, paste to connect on PC |
| **Custom Broker** | Custom broker address, port, topic with test & diagnostics |
| **Game Mode** | Auto-switch to mini floating window, restore on exit |
| **Manual HRV** | Start/stop recording, auto-save with HRV chart after ≥3 min |
| **USB Direct** | ADB port forwarding for WiFi-free connection |
| **Permissions 🛡️** | Step-by-step permission guide (Bluetooth/Location/Overlay/Notify/Battery) |

### 💻 PC

**Go Client** — `heart_client.exe`
- Single ~5.6MB binary, no runtime required
- 📡 **MQTT Remote Receiving** — paste connection code to receive HR
- Browser at `http://localhost:9091`
- OBS overlay `http://localhost:9091?transparent=1`
- USB mode with `-usb` flag

**Python Client** — `heart_monitor.py`
- tkinter GUI with device status display
- 📡 **MQTT remote receiving** with connection test
- Floating window (7 styles, lockable, adjustable font)
- Real-time HRV chart & analysis
- LAN auto-discovery + USB one-click connect
- PC Bluetooth support (requires bleak)

**Web Client** — `mqtt.html`
- Pure browser MQTT receiver, zero dependencies
- Deployable on GitHub Pages, no server needed

---

## 🚀 Quick Start

### WiFi Connection (LAN)

```bash
# 1. Enable broadcast on phone
HeartBeat → Settings → Enable "HR Broadcast"

# 2. Connect on PC
heart_client.exe 192.168.1.5:9090

# 3. Open browser
http://localhost:9091
```

### USB Direct

```bash
# 1. Connect phone via USB, enable USB debugging
# 2. Enable HR broadcast on phone
# 3. Run on PC (adb.exe must be in same directory)
heart_client.exe -usb
```

### OBS Overlay

```
OBS → Sources → + → Browser
URL: http://localhost:9091?transparent=1
Width: 200 · Height: 120
```

---

## 📡 MQTT Remote Connection

### On Phone
```
HeartBeat → Settings → MQTT Remote → Enable → Copy Connection Code
```

### On PC (3 ways)

**Method 1:** Run `heart_client.exe`, paste code, click "Connect"
**Method 2:** Run `heart_monitor.py`, paste code
**Method 3:** Open `mqtt.html` in browser, paste code

### Connection Code Format

```
HEARTBEAT#V1#GUEMSNDM#broker-cn.emqx.io:1883#heart/rate
```

| Segment | Description |
|---------|-------------|
| `HEARTBEAT#V1#` | Fixed header + protocol version |
| `GUEMSNDM` | 8-char random device ID |
| `broker-cn.emqx.io:1883` | MQTT server address + port |
| `heart/rate` | Topic (device ID auto-appended) |

Actual subscribed topic = `heart/rate/GUEMSNDM`

### Broker Options

**🆓 Public Brokers**
| Broker | Port | Notes |
|--------|------|-------|
| `broker-cn.emqx.io` | 1883 | EMQX China node, low latency ✅ |
| `broker.emqx.io` | 1883 | EMQX global cluster |
| `test.mosquitto.org` | 1883 | Eclipse Foundation |

**🏠 Docker Self-hosted**
```bash
docker run -d --name emqx \
  -p 1883:1883 -p 8083:8083 \
  -p 18083:18083 \
  emqx/emqx:latest
```
Admin: `http://your-ip:18083` (default admin/public)

**☁️ Cloud Platforms**
| Platform | Pricing | Notes |
|----------|---------|-------|
| Alibaba Cloud MQTT | From ¥5/month | Lowest latency in China |
| Huawei Cloud IoTDA | 1M msgs/month free | Enterprise-grade |
| EMQX Cloud | Serverless free tier | Global nodes |

### Troubleshooting

If no data received:
1. Click "Test Connection" to check broker reachability
2. Open `mqtt.html` in browser, paste connection code
3. If mqtt.html works → exe/py issue, re-download
4. If still nothing → check firewall or switch broker

---

## 📡 Communication Protocol

### Local Broadcast (HTTP + SSE)

Base URL: `http://<phone-IP>:9090`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Broadcast page (OBS transparent overlay) |
| `/api/hr` | GET | Current HR as JSON |
| `/api/hr` | POST | Receive HR push |
| `/api/sse` | GET | SSE real-time stream |
| `/api/info` | GET | Server info |

```json
// GET /api/hr response
{
  "hr": 72,
  "device": "Polar H10",
  "connected": true
}
```

| Port | Usage |
|------|-------|
| 9090 | Phone broadcast server |
| 9091 | PC local service / ADB forwarding |

### MQTT Remote

| Item | Description |
|------|-------------|
| **Transport** | MQTT 3.1.1, QoS 0 |
| **Data Format** | `{"hr":72,"device":"DeviceName","connected":true}` |
| **Topic** | `heart/rate/{device-id}` |
| **Broker Port** | TCP 1883 / WebSocket 8083 |

---

## 📁 Project Structure

```
Heart/
├── app/                          # Android module (Java)
│   ├── src/main/java/com/xinji/heartbeat/
│   │   ├── OnboardingActivity.java  # 🆕 Onboarding (step-by-step permissions)
│   │   ├── OnboardingPageFragment.java # 🆕 Onboarding fragments
│   │   ├── MainActivity.java     # Main UI (ViewPager2 + BottomNav)
│   │   ├── ConnectFragment.java  # BLE scanning & connection
│   │   ├── HomeFragment.java     # HR home page
│   │   ├── SettingsFragment.java # Settings (MQTT config, port, help)
│   │   ├── BroadcastActivity.java# Broadcast config (share exe/py)
│   │   ├── mqtt/MqttManager.java # MQTT push manager (zero deps)
│   │   ├── server/BroadcastServer.java # HTTP + SSE server
│   │   ├── widget/FloatWindowManager.java # Floating window manager
│   │   └── ...
│   └── src/main/assets/
│       ├── heart_client.exe           # Windows exe
│       ├── heart_monitor.py           # Python full version
│       ├── heart_monitor_headless.py  # Python headless version
│       └── mqtt.html                  # Browser MQTT receiver
│
└── pc_client/                    # Go client source
    ├── main.go                   # Win32 GUI + HTTP server
    └── mqtt.go                   # Pure Go MQTT implementation
```

### Tech Stack

| Component | Choice |
|-----------|--------|
| Android | Java, AndroidX, MPAndroidChart |
| PC (Go)  | net/http, Win32 API, Pure Go MQTT |
| PC (Python) | tkinter, paho-mqtt, bleak |
| Communication | HTTP REST + SSE + MQTT |
| MQTT Broker | EMQX (Public / Self-hosted / Cloud) |

---

## 🔧 Build Guide

```bash
# Android APK
./gradlew assembleRelease

# Go client (requires MinGW cross-compiler)
cd pc_client
CC=x86_64-w64-mingw32-gcc CGO_ENABLED=1 GOOS=windows GOARCH=amd64 \
  go build -ldflags="-H windowsgui -s -w" -o heart_client.exe
```

---

## 📋 Changelog

### v3.1 — Onboarding Redesign & Clearer Permissions (Current)

**✨ New**
- 🚀 **Brand new onboarding** — 7-step first-launch guide showcasing features
- 🛡️ **Step-by-step permissions** — Bluetooth → Overlay → Notifications → Battery optimization
- 📖 **Auto-transition** — Automatically requests permissions and enters the app after onboarding
- 🎨 **Visual polish** — ViewPager2 with animated page indicators

**🔧 Improvements**
- **Permission refactor** — Centralized permission management in OnboardingActivity
- **Decoupled design** — Pages describe features only, permissions handled by OnboardingActivity
- **Floating window stability** — FloatWindowManager logic optimized
- **BLE scan stability** — Improved Bluetooth scan callback logic
- **Device profile matching** — DeviceProfileManager logic adjusted for better accuracy

### v3.0 — MQTT Remote Push

**✨ New**
- 📡 **MQTT remote push** — Cross-internet HR push
- 🔗 **Connection code** — `HEARTBEAT#V1#` format, one-click copy
- 💻 **3-way MQTT receive** — Go exe / Python / HTML
- 🐍 **Python full version** — Floating window, HRV chart, LAN scan
- 🌐 **mqtt.html** — Pure browser receiver, GitHub Pages ready
- 🧩 **MqttManager decoupled** — Zero external deps, callback-based
- 🧩 **BleManager decoupled** — Independent of DeviceProfileManager

**🐛 Fixes**
- exe CONNECT packet missing protocol name length
- exe cross-thread UI freeze
- exe digit refresh residue
- Python floating window style scope error
- Settings page `inputType` compile error
- MQTT precise subscription failure
- Device tag placeholder collision
- 10-second timeout detection added

**🔧 Improvements**
- All exe UI operations dispatched to main thread
- MQTT wildcard `+` subscription
- Connection diagnostics

### v2.3
- USB direct connection (`-usb` flag)
- Go language PC client
- ADB auto port forwarding

### v2.2
- Game mode, device feature code auto-matching, BLE stability

### v2.1
- Python client, HRV recording, LAN auto-discovery

### v2.0
- UI rewrite, BLE layer rewrite, floating window system

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