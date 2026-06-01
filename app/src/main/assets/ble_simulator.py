# -*- coding: utf-8 -*-
"""
❤️ 心迹 — BLE 心率设备模拟器
在电脑上模拟一个蓝牙心率设备，用于测试心迹的广义搜索和筛选模式。

用法：
  python ble_simulator.py                 # 标准心率服务 (0x180D/0x2A37)
  python ble_simulator.py --mi            # 小米私有协议 (0xFEE0)
  python ble_simulator.py --both          # 同时广播两种服务
  python ble_simulator.py --name "我的手表" # 自定义设备名

依赖：
  pip install bleak pygatt
"""

import asyncio
import json
import random
import time
import sys
import struct
import signal

# ═════════════════════ 配置 ═════════════════════

DEVICE_NAME = "模拟心率 Band-9Pro"
SERVICE_MODE = "standard"  # standard / mi / both

# 标准心率服务 UUID
HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
HR_CHAR_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
HR_CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

# 小米私有协议 UUID
MI_SERVICE_UUID = "0000fee0-0000-1000-8000-00805f9b34fb"
MI_CHAR_UUID = "00000008-0000-3512-2118-0009af100700"

# 设备信息
DEVICE_INFO_SVC = "0000180a-0000-1000-8000-00805f9b34fb"
MODEL_NUM_CHAR = "00002a24-0000-1000-8000-00805f9b34fb"
SERIAL_CHAR = "00002a25-0000-1000-8000-00805f9b34fb"
FW_VERSION_CHAR = "00002a26-0000-1000-8000-00805f9b34fb"
BATTERY_SVC = "0000180f-0000-1000-8000-00805f9b34fb"
BATTERY_LEVEL_CHAR = "00002a19-0000-1000-8000-00805f9b34fb"

# BLE 广播参数
ADV_INTERVAL_MS = 200  # 广播间隔（毫秒）
HEART_RATE_MIN = 60
HEART_RATE_MAX = 120

# ═════════════════════ 心率模拟 ═════════════════════

class HeartRateSimulator:
    """模拟真实心率变化"""
    
    def __init__(self, base_bpm=72):
        self.base_bpm = base_bpm
        self.current_bpm = base_bpm
        self.trend = 0  # -1 下降, 0 平稳, 1 上升
        self.trend_timer = 0
        self.last_beat_time = time.time()
        
    def get_next_bpm(self):
        now = time.time()
        elapsed = now - self.last_beat_time
        self.last_beat_time = now
        
        # 每 5-15 秒改变趋势
        self.trend_timer += elapsed
        if self.trend_timer > random.uniform(3, 10):
            self.trend = random.choice([-1, 0, 0, 0, 1])
            self.trend_timer = 0
        
        # 根据趋势调整
        if self.trend == 1:
            self.current_bpm += random.uniform(0.5, 2.0)
        elif self.trend == -1:
            self.current_bpm -= random.uniform(0.5, 2.0)
        else:
            self.current_bpm += random.uniform(-0.5, 0.5)
        
        # 限制范围
        self.current_bpm = max(HEART_RATE_MIN, min(HEART_RATE_MAX, self.current_bpm))
        
        return int(round(self.current_bpm))
    
    def get_hr_bytes_standard(self):
        """构建标准心率格式字节 (flags + HR value)"""
        hr = self.get_next_bpm()
        flags = 0x00  # 16位心率值，无传感器接触状态
        return bytes([flags, hr])
    
    def get_hr_bytes_mi(self):
        """构建小米私有心率格式字节"""
        hr = self.get_next_bpm()
        # 小米格式：data[0]=?, data[1]=?, data[2]=心率值
        return bytes([0x02, 0x00, hr])


# ═════════════════════ BLE 服务定义 ═════════════════════

def create_standard_hr_service():
    """创建标准心率服务"""
    from pygatt import GATTCharacteristic, GATTService
    import pygatt
    
    service = GATTService(HR_SERVICE_UUID)
    
    # 心率测量特征（Notify）
    hr_char = GATTCharacteristic(
        HR_CHAR_UUID,
        [pygatt.GATTCharacteristic.Property.notify],
        None,
        None,
        [],
        bytearray()
    )
    service.add_characteristic(hr_char)
    
    return service

# ═════════════════════ 主逻辑 ═════════════════════

async def run_simulator():
    """运行 BLE 模拟器"""
    global DEVICE_NAME, SERVICE_MODE
    
    # 解析参数
    args = sys.argv[1:]
    for i, arg in enumerate(args):
        if arg == "--mi":
            SERVICE_MODE = "mi"
        elif arg == "--both":
            SERVICE_MODE = "both"
        elif arg == "--name" and i + 1 < len(args):
            DEVICE_NAME = args[i + 1]
    
    print(f"\n{'='*60}")
    print(f"❤️  心迹 BLE 心率模拟器")
    print(f"{'='*60}")
    print(f"  设备名称: {DEVICE_NAME}")
    print(f"  广播模式: {SERVICE_MODE.upper()}")
    print(f"  心率范围: {HEART_RATE_MIN}-{HEART_RATE_MAX} BPM")
    print(f"{'='*60}\n")
    
    try:
        from bleak import BleakClient
        BLEAK_AVAILABLE = True
        print("📦 bleak 可用，使用真实 BLE 广播")
        print("   ⚠️  注意：Windows 需要安装蓝牙适配器")
        print("   ⚠️  注意：Linux 需要蓝牙 dongle 或内置蓝牙\n")
    except ImportError:
        BLEAK_AVAILABLE = False
        print("⚠️ bleak 未安装，使用 HTTP API 模拟")
        print("   pip install bleak\n")
    
    if BLEAK_AVAILABLE:
        await run_bleak_simulator()
    else:
        run_http_simulator()

# ═════════════════════ 方案一：bleak 真实 BLE 广播 ═════════════════════

async def run_bleak_simulator():
    """使用 bleak 广播 BLE 设备（仅支持 Linux）"""
    print("启动 BLE 广播...")
    
    # 生成广播数据
    hr_sim = HeartRateSimulator()
    
    # Linux 下可以使用 pygatt 或直接 HCI 来广播
    # 但 bleak 只支持扫描和连接，不支持广播
    # 所以这里用 console 输出模拟数据，同时启动一个 HTTP 服务器
    # 让心迹电脑端可以通过 HTTP 连接到这个模拟器
    
    print("\n⚠️  bleak 不支持直接广播，仅 Windows/Linux 可用 pygatt")
    print("📡 改用 HTTP API 模式模拟设备...\n")
    run_http_simulator()

# ═════════════════════ 方案二：HTTP API 模拟 ═════════════════════

def run_http_simulator():
    """启动 HTTP 服务器模拟心率设备"""
    from http.server import HTTPServer, BaseHTTPRequestHandler
    import threading
    
    PORT = 9190
    hr_sim = HeartRateSimulator(base_bpm=random.randint(65, 85))
    
    print(f"📡 HTTP 模拟器启动在端口 {PORT}")
    print(f"   URL: http://localhost:{PORT}/api/hr")
    print(f"   在 Python 脚本中输入此地址连接")
    print(f"\n   💡 测试方法：")
    print(f"   1. 运行电脑端心迹 (heart_monitor.py)")
    print(f"   2. 在 BLE 连接中选择「HTTP 模拟」或手动推送")
    print(f"   3. 或直接访问 http://localhost:{PORT}/api/hr 查看数据")
    print(f"\n   ❤️  当前心率: ", end="", flush=True)
    
    class SimHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/api/hr":
                hr = hr_sim.get_next_bpm()
                data = json.dumps({
                    "hr": hr,
                    "device": DEVICE_NAME,
                    "connected": True,
                    "connect_type": f"BLE模拟({SERVICE_MODE})"
                }).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                
                # 实时显示心率
                bar = "█" * max(1, min(40, hr - 50))
                print(f"\r   ❤️  {hr} BPM {bar}", end="", flush=True)
                
            elif path == "/":
                html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>心迹模拟器</title>
<style>
body{{background:#0c0c10;color:#e2c2cf;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;}}
.card{{text-align:center;background:#141216;padding:40px;border-radius:24px;border:1px solid #ff5d7c33;}}
.hr{{font-size:80px;color:#ff5d7c;font-weight:bold;}}
.bpm{{color:#998088;font-size:18px;}}
.name{{color:#675c62;margin-top:12px;}}
</style>
<script>
async function poll(){{try{{var r=await fetch('/api/hr');var d=await r.json();document.getElementById('hr').textContent=d.hr;}}catch(e){{}}}}
setInterval(poll,1000);poll();
</script>
</head><body>
<div class="card">
<div class="hr" id="hr">{hr_sim.get_next_bpm()}</div>
<div class="bpm">BPM</div>
<div class="name">{DEVICE_NAME} ({SERVICE_MODE})</div>
</div></body></html>"""
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(html)))
                self.end_headers()
                self.wfile.write(html.encode())
            else:
                self.send_response(404)
                self.end_headers()
        
        def log_message(self, *a): pass
    
    server = HTTPServer(("0.0.0.0", PORT), SimHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    
    print(f"\n\n   🌐 Web 页面: http://localhost:{PORT}")
    print(f"   📡 API 接口: http://localhost:{PORT}/api/hr")
    print(f"\n   按 Ctrl+C 停止\n")
    
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n\n⏹  模拟器已停止")
        server.shutdown()


# ═════════════════════ 配置信息输出 ═════════════════════

def print_config():
    """打印设备配置信息，方便在手机上手动添加"""
    print(f"\n{'='*60}")
    print(f"📋 设备配置（可用于手动添加特征码）")
    print(f"{'='*60}")
    print(f"  设备名称: {DEVICE_NAME}")
    print(f"  模拟地址: 00:AA:BB:CC:DD:EE")
    
    if SERVICE_MODE in ("standard", "both"):
        print(f"\n  标准心率服务:")
        print(f"    Service: {HR_SERVICE_UUID}")
        print(f"    Char:    {HR_CHAR_UUID}")
    
    if SERVICE_MODE in ("mi", "both"):
        print(f"\n  小米私有协议:")
        print(f"    Service: {MI_SERVICE_UUID}")
        print(f"    Char:    {MI_CHAR_UUID}")
    
    print(f"\n  特征码 JSON:")
    config = [
        {
            "name": DEVICE_NAME,
            "address": "00:AA:BB:CC:DD:EE",
            "service_uuid": HR_SERVICE_UUID if SERVICE_MODE in ("standard", "both") else MI_SERVICE_UUID,
            "char_uuid": HR_CHAR_UUID if SERVICE_MODE in ("standard", "both") else MI_CHAR_UUID,
            "last_connected": time.strftime("%Y-%m-%d %H:%M:%S")
        }
    ]
    print(f"  {json.dumps(config, indent=2, ensure_ascii=False)}")
    print(f"{'='*60}\n")


# ═════════════════════ 启动 ═════════════════════

if __name__ == "__main__":
    print_config()
    
    if sys.platform == "win32":
        # Windows 需要设置事件循环策略
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    
    try:
        asyncio.run(run_simulator())
    except KeyboardInterrupt:
        print("\n\n👋 再见！")
    except Exception as e:
        print(f"\n❌ 错误: {e}")
        import traceback
        traceback.print_exc()
