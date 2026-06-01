# ❤️ 心迹 (Heart) — debug 分支

> **这是开发调试分支，包含最新的实验性功能。稳定版请切换到 main 分支。**

<p align="center">
  <strong>一款连接蓝牙心率设备的心率监测工具</strong><br>
  支持标准心率服务和小米/华米私有协议 · 实时悬浮窗 · 电脑广播联动
</p>

<p align="center">
  <img src="https://img.shields.io/badge/API-24%2B-brightgreen" alt="Min SDK">
  <img src="https://img.shields.io/badge/Version-2.3--debug-blue" alt="Version">
  <img src="https://img.shields.io/badge/Branch-debug-orange" alt="Branch">
</p>

---

## debug 分支新增功能

### 广义搜索模式
- 不过滤特征码，扫描范围内的所有 BLE 设备
- 设备列表实时更新，显示 5/5 信号强度
- 智能排序：heart/watch/band 等穿戴设备自动排前面
- 支持查看每个设备广播的 Service UUID 列表

### 筛选模式（自动匹配心率特征）
- 点击设备右侧 ⋮ → 选择「筛选模式」
- 自动连接设备，遍历所有 Service / Characteristic
- 用关键词（heart/hr/rate/心率/pulse/bpm）自动匹配心率特征
- 匹配到后自动订阅 Notify 通知，实时接收心率数据
- 如果没有任何特征匹配，回退订阅所有 Notify 特征
- 整个过程实时输出到日志框

### 实时日志系统
- 连接页面下半部分为日志区域
- 带时间戳、彩色文字区分事件类型
- ScrollView 可上下滑动，自动滚动到底部

### 连接页面重构
- 上半部分：实时设备列表（RecyclerView）
- 下半部分：日志框
- 页面切换后自动同步连接状态
- 广义搜索开关切换时自动重启扫描

---

## 快速开始

### 方式一：直接安装 APK
从本分支根目录的 app-debug.apk 下载安装

### 方式二：自行构建
```
git clone -b debug https://github.com/a2006-dev/heart.git
```
用 Android Studio 或 AndroidIDE 打开，同步 Gradle 后运行

---

## 权限说明

| 权限 | 用途 |
|------|------|
| BLUETOOTH_SCAN | 扫描蓝牙设备 |
| BLUETOOTH_CONNECT | 连接蓝牙设备 |
| ACCESS_FINE_LOCATION | 蓝牙扫描（Android 10-12） |
| POST_NOTIFICATIONS | 前台服务通知 |
| SYSTEM_ALERT_WINDOW | 心率悬浮窗 |
| PACKAGE_USAGE_STATS | 游戏模式检测 |
| FOREGROUND_SERVICE | 后台心率监测 |

---

## debug 分支更新日志

### 2025-06-01
- 新增广义搜索模式：不过滤特征码，列出所有 BLE 设备
- 新增筛选模式：自动遍历 Service/Characteristic 匹配心率特征
- 新增日志系统：实时记录蓝牙操作和心率数据
- 设备列表智能排序：watch/band/heart 设备优先
- 修复页面切换状态丢失问题
- 日志区域改为 ScrollView，支持自由滑动
- 包含最新 debug APK

---

> 免责声明：本应用仅用于健康数据展示，不做医疗诊断用途。

<p align="center">Made with ❤️</p>

---

[⬇️ 下载 heart-v2.3-debug.apk](https://github.com/a2006-dev/heart/releases/download/v2.3-debug/heart-v2.3-debug.apk) | [📦 前往 Release 页面](https://github.com/a2006-dev/heart/releases/tag/v2.3-debug)
