# 心迹 (Heart) - 心率监测 & 电脑联动

连接蓝牙心率设备 · 实时悬浮窗显示 · 电脑联动 · 手动记录

API 24+ | Version 3.1 | MIT License

## 功能特性

### 心率监测
- 蓝牙 BLE 连接，支持标准心率服务（0x180D）
- 实时波形动画
- 自动重连，记忆上次设备
- 设备断开时通知栏提醒

### 电脑联动
- HTTP + SSE 广播，浏览器实时查看心率
- OBS 直播透明背景叠加
- Python 客户端脚本接收心率
- 智能保活防止系统休眠

### 悬浮窗
- 5 种样式：默认 / 暗夜 / 烈焰 / 冰雪 / 透明
- 48px+ 大字体高清渲染
- 自由拖拽，位置自动记忆

### 手动记录
- 点击开始/停止，手动控制心率记录
- 3分钟以上自动保存
- 历史记录含心率范围、平均值、迷你折线图

## v3.1 更新内容

### Bug 修复
- 修复 Python 客户端闪退（get_ip 函数名错误等 5 处致命 Bug）
- 修复字体兼容问题
- 修复 OBS 帮助无法打开

### 清理与重构
- 移除 ZXing 扫码依赖
- 移除自动游戏检测，改为手动记录
- 统一前台通知服务

### 新增功能
- 常驻保活通知
- 通知权限引导
- 蓝牙权限运行时检查
- 设备断开通知
- 手动记录 + 迷你折线图

## 下载

https://github.com/a2006-dev/heart/releases/tag/v3.1

## 构建

git clone https://github.com/a2006-dev/heart.git
使用 Android Studio 或 AndroidIDE 打开。

## 电脑客户端

python heart_monitor.py
无桌面环境：python heart_monitor_headless.py
浏览器打开 http://本机IP:9090

## 致谢

- 心宿 (Xinxiu) - 蓝牙 BLE 模块参考
- MPAndroidChart - 心率折线图
- Material Components - UI 组件
