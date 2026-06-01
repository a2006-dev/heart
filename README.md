# ❤️ 心迹 (Heart) - 心率监测 & 游戏模式

<p align="center">
  <strong>一款精致优雅的 Android 心率监测工具</strong><br>
  连接蓝牙心率设备 · 实时悬浮窗显示 · 电脑联动 · 游戏心率记录
</p>

<p align="center">
  <img src="https://img.shields.io/badge/API-24%2B-brightgreen" alt="Min SDK">
  <img src="https://img.shields.io/badge/Version-2.2-blue" alt="Version">
  <img src="https://img.shields.io/badge/License-MIT-orange" alt="License">
  <a href="https://github.com/a2006-dev/heart"><img src="https://img.shields.io/badge/GitHub-仓库-blue?logo=github" alt="GitHub"></a>
</p>

---

## 📸 截图

| 主页心率 | 设备连接 | 游戏模式 | 悬浮窗样式 |
|---------|---------|---------|-----------|
| 精致玻璃态卡片设计 | 一键扫描蓝牙设备 | 自动记录游戏心率 | 5种精美样式 |
| 实时心率波形动画 | RSSI信号强度显示 | 折线图分析 | 拖拽/锁定/记忆 |

## ✨ 功能特性

### 🫀 心率监测
- **蓝牙 BLE 连接** — 支持标准蓝牙心率服务（0x180D）
- **实时心率显示** — 精美 WebView 动画，带心电波形效果
- **自动重连** — 记忆上次设备，蓝牙断开后自动扫描重连

### 🖥️ 电脑联动
- **局域网广播** — 同一 WiFi 下通过浏览器查看心率
- **OBS 直播支持** — 透明背景模式，适合直播场景
- **扫码连接** — 二维码扫码快速配对
- **Python 客户端** — 内置电脑端脚本，支持自定义样式

### 📱 悬浮窗
- **5 种样式** — 简约文字 / 科技胶囊 / 圆形徽章 / 心电脉搏 / 超小迷你
- **自由拖拽** — 可拖动到屏幕任意位置
- **锁定模式** — 防误触，位置记忆

### 🎮 游戏模式
- **自动识别游戏** — 添加游戏后切到前台自动记录
- **小窗/分屏支持** — 切出去刷视频、回消息不中断记录
- **心率折线图** — 每次游戏结束后查看心率变化曲线
- **数据分享** — 生成带统计数据的精美截图分享
- **通知栏控制** — 下拉通知栏一键停止记录

### 📡 心率广播（直播/推流）
- 内置 HTTP + SSE 服务器
- 电脑浏览器实时显示心率悬浮窗
- OBS 浏览器源叠加显示
- 支持推送到第三方应用

### 🎨 UI/UX
- 深色主题，玻璃态设计
- 全屏沉浸式体验
- 开机引导，权限逐步申请
- 启动动画，心率脉冲光效

## 📋 权限说明

| 权限 | 用途 |
|------|------|
| `BLUETOOTH_SCAN` (API 31+) | 扫描蓝牙心率设备 |
| `BLUETOOTH_CONNECT` (API 31+) | 连接蓝牙设备 |
| `ACCESS_FINE_LOCATION` | 蓝牙扫描需要（Android 10-12） |
| `ACCESS_BACKGROUND_LOCATION` | 后台扫描蓝牙 |
| `POST_NOTIFICATIONS` (API 33+) | 前台服务通知 |
| `SYSTEM_ALERT_WINDOW` | 心率悬浮窗 |
| `PACKAGE_USAGE_STATS` | 游戏模式检测前台应用 |
| `CAMERA` | 扫码连接电脑 |
| `FOREGROUND_SERVICE` | 后台心率监测 |

## 🛠️ 技术栈

- **语言:** Java
- **最低 SDK:** Android 7.0 (API 24)
- **目标 SDK:** Android 14 (API 34)
- **架构:** Fragment + ViewPager2 单 Activity
- **图表:** MPAndroidChart
- **扫码:** ZXing (zixing-android-embedded)
- **WebView:** 自定义心率波形引擎 (Canvas)

## 🚀 构建

```bash
# 克隆仓库
git clone https://github.com/a2006-dev/heart.git

# 使用 Android Studio 或 AndroidIDE 打开项目
# 同步 Gradle 后直接运行
```

或者直接下载 [Release 页面](https://github.com/a2006-dev/heart/releases/tag/v2.0) 的 APK 安装。

## 📦 项目结构

```
app/
├── src/main/
│   ├── Java/com/xinji/heartbeat/
│   │   ├── MainActivity.java          # 主界面 - 蓝牙/悬浮窗/广播控制
│   │   ├── SplashActivity.java         # 启动动画
│   │   ├── OnboardingActivity.java     # 引导页 + 权限申请
│   │   ├── HomeFragment.java           # 主页 WebView 心率显示
│   │   ├── ConnectFragment.java        # 设备连接页
│   │   ├── GameModeFragment.java       # 游戏模式管理
│   │   ├── SettingsFragment.java       # 设置页
│   │   ├── GameModeService.java        # 游戏模式后台检测服务
│   │   ├── HeartRateService.java       # 心率后台保活服务
│   │   ├── HeartRateManager.java       # 心率监听管理单例
│   │   ├── HeartRateBroadcastServer.java # HTTP/SSE 广播服务器
│   │   ├── BroadcastActivity.java      # 广播管理 + 扫码推送
│   │   ├── GameRecordsActivity.java    # 游戏记录图表
│   │   ├── HRMarkerView.java          # 图表标记视图
│   │   ├── QRCodeGenerator.java       # 二维码生成
│   │   └── ScanActivity.java          # 自定义扫码 Activity
│   ├── assets/
│   │   ├── index.html                 # 主页心率显示页面
│   │   └── heart_monitor.py           # 电脑端 Python 客户端
│   └── res/                            # 布局/资源文件
└── build.gradle                        # 构建配置
```

## 📝 更新日志

### v3.0（修复版）
- 🔧 **统一端口**：手机端、电脑端统一使用端口 9090（原先手机端显示 8080，电脑端用 9090/9091，开箱不通）
- 🔧 **单端口路由**：电脑端 HTTP 页面 + API + SSE 合并到同一个端口，通过路径区分（`/`/`/api/hr`/`/stream`）
- 🔧 **心率源优先级**：蓝牙(最高) > ADB > 手机推送，防止多源互相覆盖
- 🔧 **WebView JS 注入修复**：设备名使用 `JSONArray` 序列化转义，避免单引号/反斜杠导致 JS 语法错误
- 🔧 **蓝牙扫描 Handler 分离**：手动扫描和自动扫描使用不同 Handler，互不干扰
- 🔧 **ADB 默认端口修正**：默认从 9091 → 9090（与统一端口一致）
- 🔧 **移除已关停的 JCenter 仓库**：加速 Gradle 构建
- 🔧 **OBS 透明模式**：自动生成 `?transparent=1` 链接
- 🔧 **5秒心跳检测**：无数据更新时悬浮窗闪烁警告
- 🔧 **点击穿透**：游戏模式下悬浮窗支持鼠标点击穿透

### v3.1（代码审查修复）
- 🔧 **SSE 长连接修复**：修复 `handleClient` 中 `finally{safeClose}` 误关 SSE 长连接的致命 Bug
- 🔧 **SSE 超时检测**：增加 `SocketTimeoutException` 处理，避免读取阻塞导致线程无法退出
- 🔧 **移除冗余推送机制**：删除 `pushHR`/`startPushing`/`stopPushing` 整个 HTTP 客户端推送（手机SSE服务器已覆盖所有场景）
- 🔧 **移除局域网暴力扫描**：删除 `scanNetworkDevices`（255个串行 ping 耗时 38s，实用价值低，扫码连接已足够）
- 🔧 **修复监听器泄漏**：`stopPushing` 中 lambda 表达式无法匹配已注册监听器的问题（该段代码已整体移除）
- 🔧 **WebView JS 注入终极修复**：`JSONArray.toString()` 直接作为 JS 字符串字面量，不再 substring 取巧
- 🔧 **弹窗样式冲突标记**：`GameModeFragment` 明确注释避免使用 `setMultiChoiceItems`

### v2.2
- 🚀 内存优化：WebView 暂停/恢复渲染，onLowMemory 回调，onDestroy 完全释放引用
- 🐛 修编译错误：BroadcastActivity.java 变量名冲突
- 🐙 设置页加 GitHub 仓库直达链接
- 📡 Python 脚本悬浮窗显示连接方式（局域网/有线）
- 🔗 代码加心宿（milirstudio/xinxiu）蓝牙模块引用

### v2.1
- 🚀 内存优化：HeartRateManager 弱引用监听器，防止泄露
- 🐙 加 GitHub 仓库链接
- 📡 Python 脚本加连接方式显示
- 🔗 代码加心宿引用

### v2.0
- ✨ 全新 UI 设计：玻璃态卡片、深色主题
- 🎮 游戏模式：自动识别、心率记录、折线图分析
- 📡 心率广播：电脑浏览器/OBS 实时显示
- 📱 5 种悬浮窗样式
- 🔄 自动重连、悬浮窗位置记忆
- 🎬 启动动画 + 引导页
- 📷 扫码连接电脑

### v1.0
- 初始版本：蓝牙心率连接、基础悬浮窗

## 📬 联系与反馈

- **QQ:** 3544399875
- **反馈:** [Issues](https://github.com/a2006-dev/heart/issues)

## 🙏 致谢 & 引用

- **心宿 (Xinxiu)** — 本项目蓝牙 BLE 扫描/连接模块参考了 [milirstudio/xinxiu](https://github.com/milirstudio/xinxiu)（米粒工作室），感谢其开源贡献。
- [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) — 心率折线图
- [ZXing](https://github.com/zxing/zxing) — 二维码扫码
- [Material Components](https://github.com/material-components/material-components-android) — UI 组件

## ⚠️ 免责声明

本应用仅用于健康数据展示，不做医疗诊断用途。心率数据仅供参考，如有心脏不适请及时就医。

---

<p align="center">Made with ❤️</p>
<p align="center"><a href="https://github.com/a2006-dev/heart">GitHub 仓库</a></p>
