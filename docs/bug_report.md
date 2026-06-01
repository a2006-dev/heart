# 🐛 心迹 (Heart) Bug 报告

## 🔴 Bug 1：游戏模式-应用选择弹窗多选样式失效

- **位置：** `GameModeFragment.java` 第128-129行
- **原因：** `setAdapter(arrayAdapter, null)` 和 `setMultiChoiceItems(…)` 互相冲突，后者覆盖前者
- **结果：** 自定义白色文字样式失效，在深色主题下文字几乎无法辨认

---

## 🟡 Bug 2：同名应用选择时包名映射错误

- **位置：** `GameModeFragment.java` 第99-101行 / 第136-137行
- **原因：** 以应用名（`name`）作为 Map 的 key，同名应用会互相覆盖
- **结果：** 选择了 "计算器A" 可能实际添加的是 "计算器B"

---

## 🟡 Bug 3：手动扫描与自动扫描的 Handler 回调互相干扰

- **位置：** `MainActivity.java` 第196行 / 第222行
- **原因：** `stopScan()` 调用 `scanHandler.removeCallbacksAndMessages(null)` 会清除所有回调
- **结果：** 手动扫描期间若自动扫描触发，超时回调被清除，可能导致扫描永远不停止

---

## 🟡 Bug 4：设备名未转义导致 WebView JS 执行错误

- **位置：** `HomeFragment.java` 第28行
- **原因：** 直接拼接字符串到 JS 代码中
- **示例：** 设备名为 `O'Connor HR` 时，JS 变为 `setDeviceName('O'Connor HR')`，语法错误

---

## 🟡 Bug 5：UsageStatsManager 获取前台应用不可靠

- **位置：** `GameModeService.java` 第133-148行
- **原因：** `getLastTimeUsed` 不精确反映当前前台应用
- **结果：** 在多任务切换/画中画模式下可能误判，导致游戏模式记录不准确

---

## 🟢 Bug 6：已关停的 bintray 仓库地址

- **位置：** `settings.gradle` 第6行
- **原因：** Bintray/JCenter 已于2021年关停
- **影响：** Gradle 解析时会超时，拖慢构建速度

---

## 🟢 Bug 7：监听器列表非线程安全

- **位置：** `HeartRateManager.java` 第10行
- **原因：** `ArrayList` 在多线程遍历+修改会抛 `ConcurrentModificationException`
- **建议：** 改用 `CopyOnWriteArrayList`

---

## 🟢 Bug 8：游戏记录数据末尾多余逗号

- **位置：** `GameModeService.java` 第186行
- **原因：** 始终追加逗号，结果为 `70,80,90,`
- **影响：** `GameRecordsActivity` 虽有 `isEmpty()` 过滤，但数据不严谨

---

## 严重性分级

| 级别 | 数量 |
|------|------|
| 🔴 严重 | 1 |
| 🟡 中等 | 4 |
| 🟢 轻微 | 3 |
