# -*- coding: utf-8 -*-
"""
❤️ 心迹 v3.0 — 电脑端（重构版）
修复内容：
  - BLE 连接改为真实 GATT 监听（读取心率特征值 0x2A37）
  - 内嵌 HTML 抽离为独立文件（heart_monitor_page.html）
  - 悬浮窗位置持久化记忆（JSON 文件）
  - 端口分配状态明确显示给用户
  - 移除「进程保护」(svchost.exe 伪装)
  - ADB 线程使用 Event 安全终止
  - HRV 开始前检查设备连接状态
  - 帮助页面增加 OBS 配置说明
  - 版本号统一为 v3.0
"""
import json, threading, time, webbrowser, socket, os, sys, subprocess, importlib, urllib.request
from http.server import HTTPServer, BaseHTTPRequestHandler
from dataclasses import dataclass, field
from typing import List, Tuple, Optional, Callable

_DEMO = False

# ═════════════════════ 环境检查 ═════════════════════

def auto_install(pkg, name=None, label=""):
    tag = label or pkg
    try: importlib.import_module(name or pkg); return True
    except ImportError:
        print(f"📦 安装 {tag}...")
        try:
            subprocess.check_call([sys.executable,"-m","pip","install",pkg,"-q",
                "-i","https://pypi.tuna.tsinghua.edu.cn/simple"],stderr=subprocess.DEVNULL,timeout=60)
            try: importlib.import_module(name or pkg); print(f"✅ {tag} 完成"); return True
            except: print(f"⚠️ {tag} 失败"); return False
        except: print(f"⚠️ {tag} 失败，手动: pip install {pkg}"); return False

def setup():
    print("🔄 心迹 v3.0 检查环境...")
    if sys.version_info<(3,7): print("需要 Python 3.7+"); input("按 Enter 退出..."); sys.exit(1)
    try: 
        import tkinter as tk
        print("✅ tkinter 正常")
    except:
        print("⚠️ tkinter 未完全安装，部分功能可能不可用")
        if sys.platform=="linux":
            subprocess.run(["apt","install","-y","python3-tk"],capture_output=True,timeout=120)
    print("✅ 环境就绪（如需蓝牙功能请确保已安装 bleak: pip install bleak）\n")
setup()

# ═════════════════════ 全局状态（结构化） ═════════════════════

UNIFIED_PORT = 9090  # 统一端口：页面 + API 都在这里

# 心率源优先级
SRC_BLE = 3   # 蓝牙直接连接（最高优先级）
SRC_ADB = 2   # ADB 拉取
SRC_PUSH = 1  # 手机推送（最低优先级）

@dataclass
class HeartState:
    hr: int = 0
    connected: bool = False
    device_name: str = ""
    conn_type: str = "未连接"
    source_priority: int = 0  # 当前数据源的优先级
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def update(self, v: int, name: str = "", ctype: str = "", priority: int = 0):
        """带优先级的心率更新：高优先级覆盖低优先级"""
        with self._lock:
            if not (0 < v < 250):
                return
            if priority < self.source_priority and self.connected:
                return
            self.hr = v
            self.connected = True
            self.source_priority = priority
            if name:
                self.device_name = name
            if ctype:
                self.conn_type = ctype

    def snapshot(self) -> dict:
        with self._lock:
            return {"hr": self.hr, "connected": self.connected,
                    "device": self.device_name, "type": self.conn_type}

state = HeartState()
LOGS: List[str] = []
_IP: Optional[str] = None

# ═════════════════════ 核心函数 ═════════════════════

def ip():
    global _IP
    if _IP: return _IP
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1); s.connect(("8.8.8.8", 80))
        _IP = s.getsockname()[0]; s.close()
    except:
        for i in socket.getaddrinfo(socket.gethostname(), None):
            a = i[4][0]
            if a.startswith("192.168.") or a.startswith("10."):
                _IP = a; break
    if not _IP: _IP = "127.0.0.1"
    return _IP

def log(m):
    LOGS.append(m)
    if len(LOGS) > 30: LOGS.pop(0)
    print(f"[心迹] {m}")

def find_free_port(base: int, max_try: int = 10) -> int:
    """查找可用端口，明确告知用户实际使用的端口"""
    for i in range(max_try):
        p = base + i
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(1)
            s.bind(("0.0.0.0", p))
            s.close()
            if i > 0:
                log(f"📌 端口 {base} 被占用，改用了 {p}")
            return p
        except:
            continue
    log(f"⚠️ 端口 {base}~{base+max_try-1} 均被占用，强行使用 {base}")
    return base

def try_firewall(port: int):
    if sys.platform != "win32": return
    try:
        r = subprocess.run(f'netsh advfirewall firewall show rule name="心迹"',
                           shell=True, capture_output=True, text=True, timeout=5)
        if "心迹" in r.stdout: return
        subprocess.run(
            f'netsh advfirewall firewall add rule name="心迹" dir=in action=allow protocol=tcp localport={port}',
            shell=True, capture_output=True, timeout=10)
    except:
        pass

# ═════════════════════ HTTP 服务器（HTML 从独立文件读取） ═════════════════════

def _load_html() -> str:
    """从同目录下的 HTML 模板文件加载，不存在则用内嵌默认值"""
    _dir = os.path.dirname(os.path.abspath(__file__)) if "__file__" in dir() else "."
    _path = os.path.join(_dir, "heart_monitor_page.html")
    if os.path.exists(_path):
        try:
            with open(_path, "r", encoding="utf-8") as f:
                return f.read()
        except:
            pass
    return _default_html()

def _default_html() -> str:
    """内嵌默认 OBS 浏览器源页面（纯 CSS 透明背景心率显示）"""
    return f'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>心迹 - OBS 悬浮窗</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box;user-select:none;}}
body{{width:100vw;height:100vh;overflow:hidden;font-family:'Inter',-apple-system,sans-serif;background:transparent !important;display:flex;align-items:center;justify-content:center;}}
.float-window{{position:fixed;top:20px;right:20px;cursor:grab;z-index:9999;display:flex;flex-direction:column;align-items:center;gap:4px;padding:10px 16px;background:rgba(12,12,16,0.7);backdrop-filter:blur(16px);border-radius:16px;border:1px solid rgba(255,93,124,0.25);box-shadow:0 4px 20px rgba(0,0,0,0.5);}}
.float-window.transparent-mode{{background:transparent !important;backdrop-filter:none !important;border:none !important;box-shadow:none !important;padding:0 !important;gap:0 !important;}}
.hr-row{{display:flex;align-items:baseline;gap:4px;}}
.hr-number{{font-size:2.6rem;font-weight:700;font-family:monospace;color:#ff5d7c;text-shadow:0 0 14px rgba(255,60,100,0.5);line-height:1;letter-spacing:-1px;}}
.hr-unit{{font-size:0.8rem;color:#ffb0bd;font-weight:500;}}
.device-bar{{display:flex;align-items:center;gap:6px;margin-top:1px;}}
.pulse-dot{{width:6px;height:6px;border-radius:50%;background:#f44b6e;box-shadow:0 0 6px #ff3e64;transition:transform 0.1s cubic-bezier(0.2,1.3,0.8,1);}}
.pulse-dot.beat{{transform:scale(1.8);}}
.device-name{{font-size:0.6rem;color:#998088;}}
.disconnected .hr-number{{color:#665c62;text-shadow:none;}}
.disconnected .pulse-dot{{background:#444;box-shadow:none;}}
</style>
</head>
<body>
<div class="float-window" id="fw">
  <div class="hr-row"><span class="hr-number" id="hrV">--</span><span class="hr-unit">BPM</span></div>
  <div class="device-bar"><div class="pulse-dot" id="pd"></div><span class="device-name" id="devN">等待连接...</span></div>
</div>
<script>
if(location.search.includes('transparent=1')||location.search.includes('transparent=true')){{document.getElementById('fw').classList.add('transparent-mode');}}
var fw=document.getElementById('fw'),drag=false,sx,sy,ox,oy;
fw.addEventListener('mousedown',function(e){{drag=true;sx=e.clientX;sy=e.clientY;ox=fw.offsetLeft;oy=fw.offsetTop;e.preventDefault();}});
document.addEventListener('mousemove',function(e){{if(!drag)return;fw.style.left=(ox+e.clientX-sx)+'px';fw.style.top=(oy+e.clientY-sy)+'px';fw.style.right='auto';}});
document.addEventListener('mouseup',function(){{drag=false;}});
function upd(d){{var el=document.getElementById('hrV'),win=document.getElementById('fw');if(d.connected&&d.hr>30&&d.hr<220){{el.innerText=d.hr;win.classList.remove('disconnected');var dot=document.getElementById('pd');dot.classList.remove('beat');void dot.offsetWidth;dot.classList.add('beat');setTimeout(function(){{dot.classList.remove('beat');}},200);}}else{{el.innerText='--';win.classList.add('disconnected');}}document.getElementById('devN').innerText=d.device||'等待连接...';}}
var es;function cs(){{es=new EventSource('/');es.onmessage=function(e){{try{{upd(JSON.parse(e.data));}}catch(err){{}}}};es.onerror=function(){{es.close();setTimeout(cs,2000);}};}}
function sp(){{setInterval(function(){{fetch('/api/hr').then(function(r){{return r.json();}}).then(upd).catch(function(){{}});}},1000);}}
if(window.EventSource){{cs();setTimeout(function(){{if(document.getElementById('hrV').innerText==='--'){{if(es)es.close();sp();}}}},4000);}}else{{sp();}}
</script>
</body>
</html>'''

_HTML_PAGE = _load_html()

def _start_server():
    """统一端口服务器：同时提供页面和 API"""
    global UNIFIED_PORT
    UNIFIED_PORT = find_free_port(UNIFIED_PORT)

    class UnifiedHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/stream":
                self._handle_sse()
            elif path == "/api/hr":
                self._handle_json()
            elif path == "/api/ip":
                self._handle_ip()
            else:
                self._handle_page()

        def do_POST(self):
            if self.path == "/api/hr":
                self._handle_push()
            else:
                self.send_response(404)
                self.end_headers()

        def _handle_page(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html;charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(_HTML_PAGE.encode("utf-8"))

        def _handle_json(self):
            s = state.snapshot()
            self.send_response(200)
            self.send_header("Content-Type", "application/json;charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(s).encode("utf-8"))

        def _handle_ip(self):
            data = json.dumps({"ip": ip(), "port": UNIFIED_PORT})
            self.send_response(200)
            self.send_header("Content-Type", "application/json;charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data.encode("utf-8"))

        def _handle_sse(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()
            try:
                while True:
                    s = state.snapshot()
                    self.wfile.write(f"data: {json.dumps(s)}\n\n".encode("utf-8"))
                    self.wfile.flush()
                    time.sleep(0.5)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except:
                pass

        def _handle_push(self):
            length = int(self.headers.get("Content-Length", 0))
            if length > 0:
                try:
                    body = self.rfile.read(length).decode("utf-8")
                    d = json.loads(body)
                    v = d.get("hr", 0)
                    if isinstance(v, (int, float)) and 0 < v < 250:
                        state.update(int(v), d.get("device", "手机"),
                                     d.get("connect_type", "WiFi"), priority=SRC_PUSH)
                except:
                    pass
            self.send_response(200)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

        def log_message(self, *a): pass

    try_firewall(UNIFIED_PORT)
    server = HTTPServer(("0.0.0.0", UNIFIED_PORT), UnifiedHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    log(f"🌐 服务器启动: http://{ip()}:{UNIFIED_PORT}")
    log(f"📱 手机推送地址: http://{ip()}:{UNIFIED_PORT}/api/hr")
    log(f"🎬 OBS 透明模式: http://{ip()}:{UNIFIED_PORT}/?transparent=1")

# ═════════════════════ BLE 蓝牙（真实 GATT 监听） ═════════════════════
# 修复：原版 connect_ble 只标记状态不实际连接
# 现在通过 bleak 真正的 BLE 连接 + 订阅心率特征值 0x2A37

_ble_connected = False
_ble_device_name = ""
_ble_client = None
_ble_stop_event = threading.Event()

def scan_ble(cb: Callable):
    def _r():
        try:
            import bleak, asyncio
            async def _scan():
                from bleak import BleakScanner
                d = await BleakScanner.discover(timeout=3.0)
                return [(dd.address, dd.name or dd.address, dd.rssi or -100) for dd in d if dd.name]
            devs = asyncio.run(_scan())
            cb(devs if devs else [])
        except:
            cb([])
    threading.Thread(target=_r, daemon=True).start()

def connect_ble(addr: str, name: str = ""):
    """真实的 BLE 连接 + 心率特征值订阅"""
    global _ble_connected, _ble_device_name, _ble_client, _ble_stop_event
    if _ble_connected:
        log("⚠️ 已有蓝牙连接，请先断开")
        return False

    _ble_device_name = name or addr
    _ble_stop_event.clear()

    def _connect_thread():
        global _ble_connected, _ble_client
        try:
            import asyncio
            from bleak import BleakClient

            # 标准蓝牙心率服务 UUID
            HR_CHAR_UUID = "00002a37-0000-1000-8000-00805f9b34fb"

            async def run():
                client = BleakClient(addr)
                await client.connect(timeout=10.0)
                _ble_client = client
                _ble_connected = True
                state.update(0, _ble_device_name, "蓝牙", priority=SRC_BLE)
                log(f"✅ 蓝牙已连接: {_ble_device_name}")

                def hr_notification(sender, data):
                    if len(data) < 2:
                        return
                    flags = data[0]
                    if flags & 0x01:
                        hr_val = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8)
                    else:
                        hr_val = data[1] & 0xFF
                    state.update(hr_val, _ble_device_name, "蓝牙", priority=SRC_BLE)

                await client.start_notify(HR_CHAR_UUID, hr_notification)

                # 保持线程存活，直到收到停止信号
                while not _ble_stop_event.is_set():
                    await asyncio.sleep(1)

                await client.stop_notify(HR_CHAR_UUID)
                await client.disconnect()
                _ble_connected = False
                _ble_client = None
                state.connected = False
                state.conn_type = "未连接"
                log("🔌 蓝牙已断开")

            asyncio.run(run())
        except Exception as e:
            log(f"❌ 蓝牙连接失败: {e}")
            _ble_connected = False
            _ble_client = None
            state.connected = False

    threading.Thread(target=_connect_thread, daemon=True).start()
    return True

def stop_ble():
    """断开蓝牙连接"""
    global _ble_connected, _ble_device_name
    if _ble_connected:
        _ble_stop_event.set()
    _ble_device_name = ""
    state.connected = False
    state.conn_type = "未连接"
    log("🔌 正在断开蓝牙...")

# ═════════════════════ ADB 心率拉取（线程安全终止） ═════════════════════

_adb_running = False
_adb_thread: Optional[threading.Thread] = None
_adb_stop_event = threading.Event()
_adb_device = ""

def start_adb_pull(device_addr: str):
    global _adb_running, _adb_thread, _adb_device, _adb_stop_event
    if _adb_running:
        stop_adb_pull()
    _adb_stop_event.clear()
    _adb_device = device_addr
    _adb_running = True
    _adb_thread = threading.Thread(target=_adb_loop, daemon=True)
    _adb_thread.start()
    log(f"📡 ADB 拉取中 → {device_addr}")

def stop_adb_pull():
    global _adb_running, _adb_thread
    _adb_running = False
    _adb_stop_event.set()
    _adb_thread = None

def _adb_loop():
    while _adb_running and not _adb_stop_event.is_set():
        try:
            url = f"http://{_adb_device}/api/hr"
            resp = urllib.request.urlopen(url, timeout=2)
            d = json.loads(resp.read().decode())
            v = d.get("hr", 0)
            if isinstance(v, (int, float)) and 0 < v < 250:
                state.update(int(v), d.get("device", "手机"), "ADB", priority=SRC_ADB)
            resp.close()
        except:
            pass
        _adb_stop_event.wait(2)

# ═════════════════════ 手动记录 + HRV ═════════════════════

_recording = False
_rec_t0 = 0.0
_rec_log: List[Tuple[float, int]] = []

def start_recording():
    """开始 HRV 记录（先检查设备是否已连接）"""
    global _recording, _rec_t0, _rec_log
    if _recording:
        return True
    s = state.snapshot()
    if not s["connected"] or s["hr"] <= 0:
        log("⚠️ 请先连接心率设备再开始记录")
        return False
    _rec_log.clear()
    _rec_t0 = time.time()
    _recording = True
    log("⏺ 开始记录心率（HRV曲线需≥3分钟数据）")
    return True

def stop_recording():
    global _recording
    if not _recording:
        return
    _recording = False
    dur = time.time() - _rec_t0
    mins = int(dur // 60)
    secs = int(dur % 60)
    log(f"⏹ 记录结束 ({mins}分{secs}秒)")
    if dur >= 180 and len(_rec_log) >= 10:
        _redraw_hrv()
        log("📈 已绘制 HRV 曲线（≥3分钟）")
    else:
        _clear_hrv()
        log(f"⏳ 记录时长 {mins}分{secs}秒，不足3分钟，不绘制曲线")

def _on_hr_rec(v: int):
    if _recording:
        _rec_log.append((time.time(), v))

# 用 state 接管原 on_hr，HRV 通过它记录
_orig_on_hr = state.update
def on_hr(v, s, t):
    state.update(v, s, t)
    _on_hr_rec(v)

_hrv_canvas = None
_hrv_label = None

def _redraw_hrv():
    if not _hrv_canvas or len(_rec_log) < 10:
        return
    _hrv_canvas.delete("all")
    w = 440; h = 160; pad = 15
    dw = w - 2 * pad; dh = h - 2 * pad
    for i in range(5):
        y = pad + i * dh / 4
        _hrv_canvas.create_line(pad, y, w - pad, y, fill="#1a1a24", width=1)
    for i in range(7):
        x = pad + i * dw / 6
        _hrv_canvas.create_line(x, pad, x, h - pad, fill="#1a1a24", width=1)
    vals = [v for _, v in _rec_log]
    vmin = min(vals); vmax = max(vals)
    vrange = max(vmax - vmin, 10)
    pts = []
    for i, (t, v) in enumerate(_rec_log):
        x = pad + (i / (len(_rec_log) - 1)) * dw
        y = pad + dh - ((v - vmin) / vrange) * dh
        pts.extend([x, y])
    if len(pts) >= 4:
        _hrv_canvas.create_line(*pts, fill="#ff5d7c", width=2, smooth=True)
    lv = vals[-1]
    ly = pad + dh - ((lv - vmin) / vrange) * dh
    _hrv_canvas.create_oval(w - pad - 4, ly - 4, w - pad + 4, ly + 4,
                             fill="#ff5d7c", outline="white", width=1)
    _hrv_canvas.create_text(w - pad - 12, ly, text=str(lv), fill="white",
                             font=("Consolas", 8, "bold"), anchor="e")
    _hrv_canvas.create_text(pad, pad, text=f"{vmax}", fill="#998088",
                             font=("Consolas", 7), anchor="nw")
    _hrv_canvas.create_text(pad, h - pad, text=f"{vmin}", fill="#998088",
                             font=("Consolas", 7), anchor="sw")
    if _hrv_label:
        _hrv_label.config(text=f"HRV 记录: {len(_rec_log)} 条 ({int((time.time()-_rec_t0)//60)}分)")

def _clear_hrv():
    if not _hrv_canvas:
        return
    _hrv_canvas.delete("all")
    _hrv_canvas.create_text(220, 80, text="不足3分钟，不绘制曲线",
                            fill="#998088", font=("sans-serif", 9))
    if _hrv_label:
        _hrv_label.config(text="HRV 记录: 0 条")

# ═════════════════════ 悬浮窗（位置持久化） ═════════════════════
# 修复：悬浮窗位置通过 JSON 文件持久化记忆

_FLOAT_POS_FILE = os.path.join(os.path.expanduser("~"), ".heart_float_pos.json")
_float_win = None
_float_showing = False
_float_label = None
_float_preset = "默认"
_protect = False  # 进程保护状态
_float_presets = {"默认": {"bg": "#0c0c10", "fg": "#ff5d7c", "size": 130, "border": "#ff5d7c", "fontsize": 48},
                  "暗夜": {"bg": "#000000", "fg": "#00ff88", "size": 120, "border": "#00ff88", "fontsize": 44},
                  "烈焰": {"bg": "#1a0000", "fg": "#ff4400", "size": 140, "border": "#ff4400", "fontsize": 52},
                  "冰雪": {"bg": "#001a2e", "fg": "#00ccff", "size": 130, "border": "#00ccff", "fontsize": 48},
                  "透明": {"bg": "#0c0c10", "fg": "#ffffff", "size": 110, "border": "#ffffff", "fontsize": 40}}

def _save_float_pos(x: int, y: int):
    try:
        with open(_FLOAT_POS_FILE, "w") as f:
            json.dump({"x": x, "y": y}, f)
    except:
        pass

def _load_float_pos() -> Tuple[Optional[int], Optional[int]]:
    try:
        with open(_FLOAT_POS_FILE, "r") as f:
            d = json.load(f)
            return d.get("x"), d.get("y")
    except:
        return None, None

def _toggle_float():
    if _float_showing: _close_float()
    else: _create_float()

def _close_float():
    global _float_win, _float_showing, _float_label
    _float_showing = False
    if _float_win:
        try: _float_win.destroy()
        except: pass
    _float_win = None; _float_label = None
    log("🔽 悬浮窗已关闭")

def _create_float():
    global _float_win, _float_showing, _float_label
    if _float_showing: _close_float()
    try:
        p = _float_presets.get(_float_preset, _float_presets["默认"])
        _float_win = tk.Toplevel(root)
        _float_win.title("心迹悬浮窗"); _float_win.overrideredirect(True)
        _float_win.attributes("-topmost", True); _float_win.configure(bg=p["bg"])

        sw = root.winfo_screenwidth()
        # 从持久化文件读取位置
        sx, sy = _load_float_pos()
        if sx is None:
            sx = sw - p["size"] - 20
            sy = 50
        _float_win.geometry(f"{p['size']}x{int(p['size']*0.7)}+{sx}+{sy}")

        f = tk.Frame(_float_win, bg=p["bg"], highlightbackground=p["border"],
                     highlightthickness=2, highlightcolor=p["border"])
        f.pack(fill="both", expand=True)
        tf = tk.Frame(f, bg=p["bg"]); tf.pack(fill="x", padx=4, pady=(2,0))
        tk.Label(tf, text="❤️ 心迹", font=("sans-serif",7,"bold"),
                 fg=p["fg"], bg=p["bg"]).pack(side="left")
        tk.Label(tf, text="常驻", font=("sans-serif",6),
                 fg="#675c62", bg=p["bg"]).pack(side="right")

        fs = p.get("fontsize", p["size"]//4)
        s = state.snapshot()
        init_hr = str(s["hr"]) if s["connected"] and s["hr"] > 30 else "--"
        _float_label = tk.Label(f, text=init_hr, font=("Consolas", fs, "bold"),
                                fg=p["fg"], bg=p["bg"])
        _float_label.pack(pady=(0,0))
        tk.Label(f, text="BPM", font=("sans-serif",7), fg=p["fg"],
                 bg=p["bg"]).pack(anchor="e", padx=6, pady=(0,2))

        # 拖拽 + 位置持久化
        def _drag_start(e):
            _float_win._dragx = e.x; _float_win._dragy = e.y
        def _drag_move(e):
            dx = e.x - _float_win._dragx; dy = e.y - _float_win._dragy
            x = _float_win.winfo_x() + dx; y = _float_win.winfo_y() + dy
            _float_win.geometry(f"+{x}+{y}")
        def _drag_stop(e):
            _save_float_pos(_float_win.winfo_x(), _float_win.winfo_y())

        for child in [f, tf, _float_label] + list(f.winfo_children()) + list(tf.winfo_children()):
            try:
                child.bind("<Button-1>", _drag_start)
                child.bind("<B1-Motion>", _drag_move)
                child.bind("<ButtonRelease-1>", _drag_stop)
            except: pass

        def _float_ctx(e):
            m = tk.Menu(_float_win, tearoff=0, bg="#1a1a24", fg="#e2c2cf",
                        activebackground="#ff5d7c", activeforeground="#fff")
            m.add_command(label=f"🎨 当前样式: {_float_preset}", state="disabled")
            m.add_separator()
            for nm, _p in _float_presets.items():
                icon = "●" if nm == _float_preset else "○"
                m.add_command(label=f"  {icon} {nm}", font=("sans-serif",9),
                              command=lambda n=nm: _change_float_preset(n))
            m.add_separator()
            m.add_command(label="🔲 调透明度", command=_float_opacity_dialog)
            m.add_command(label="📏 调字号", command=_float_fontsize_dialog)
            m.add_separator()
            m.add_command(label="🚪 关闭悬浮窗", command=_toggle_float)
            try: m.tk_popup(e.x_root, e.y_root)
            finally: m.grab_release()
        f.bind("<Button-3>", _float_ctx)

        def _float_upd():
            if not _float_showing or not _float_win: return
            try:
                s = state.snapshot()
                v = s["hr"] if s["connected"] and s["hr"] > 30 else 0
                _float_label.config(text=str(v) if v else "--")
                if v:
                    if v < 70: _float_label.config(fg="#4CAF50")
                    elif v < 100: _float_label.config(fg="#ff5d7c")
                    elif v < 140: _float_label.config(fg="#ff3d00")
                    else: _float_label.config(fg="#d50000")
                else: _float_label.config(fg=p["fg"])
                _float_win.after(500, _float_upd)
            except: pass
        _float_win.after(500, _float_upd)
        _float_showing = True
        log("🔼 悬浮窗已开启")
    except Exception as e:
        log(f"❌ 悬浮窗: {e}")

def _change_float_preset(name):
    global _float_preset
    _float_preset = name; log(f"🎨 悬浮窗样式: {name}")
    if _float_showing: _close_float(); root.after(100, _create_float)

def _float_opacity_dialog():
    v = simpledialog.askfloat("悬浮窗透明度","0.1 ~ 1.0:", initialvalue=0.9, minvalue=0.1, maxvalue=1.0)
    if v and _float_win: _float_win.attributes("-alpha", v)

def _float_fontsize_dialog():
    v = simpledialog.askinteger("悬浮窗字号","12 ~ 60:", initialvalue=30, minvalue=12, maxvalue=60)
    if v and _float_label: _float_label.config(font=("Consolas", v, "bold"))

# ═════════════════════ 进程保活 ═════════════════════
# 隐藏控制台窗口，伪装进程名，防误关

def _toggle_protect():
    global _protect
    _protect = not _protect
    if _protect:
        try:
            # 防止系统休眠（仅 Windows）
            if sys.platform == "win32":
                import ctypes
                ES_CONTINUOUS = 0x80000000
                ES_SYSTEM_REQUIRED = 0x00000001
                ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)
            log("🛡️ 保活已开启（防止系统休眠）")
        except:
            log("⚠️ 保活功能不支持当前系统")
            _protect = False
    else:
        if sys.platform == "win32":
            try:
                import ctypes
                ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)
            except: pass
        log("🛡️ 保活已关闭")

# ═════════════════════ UI 界面 ═════════════════════

try: import tkinter as tk; from tkinter import simpledialog,messagebox,colorchooser,ttk
except: 
    print("⚠️ tkinter 导入失败，尝试使用无头模式")
    # 这里继续尝试运行，GUI 相关功能会报错但不至于闪退

BG="#0c0c10"; CD="#1a1a24"; PK="#ff5d7c"; FG="#e2c2cf"; DM="#998088"; GN="#4CAF50"
CFG={"bg":"#0c0c10","fg":"#ff5d7c","fs":36,"op":0.8}

root=tk.Tk(); root.title("❤️ 心迹 v3.0 — 电脑端"); root.configure(bg=BG)
root.geometry("520x840"); root.minsize(440,660)

# 可滚动画布
_canvas=tk.Canvas(root,bg=BG,highlightthickness=0)
_scrollbar=tk.Scrollbar(root,orient="vertical",command=_canvas.yview,
                        bg=CD,troughcolor=BG,activebackground=PK,width=12,bd=0)
_canvas.configure(yscrollcommand=_scrollbar.set)
_scrollbar.pack(side="right",fill="y")
_canvas.pack(side="left",fill="both",expand=True)
_canvas_frame=tk.Frame(_canvas,bg=BG)
_canvas_window=_canvas.create_window((0,0),window=_canvas_frame,anchor="nw",width=_canvas.winfo_reqwidth())
def _cc(e): _canvas.itemconfig(_canvas_window,width=e.width); _canvas.configure(scrollregion=_canvas.bbox("all"))
_canvas.bind("<Configure>",_cc)
def _cfc(e): _canvas.configure(scrollregion=_canvas.bbox("all"))
_canvas_frame.bind("<Configure>",_cfc)
def _mw(e): _canvas.yview_scroll(int(-1*(e.delta/120)),"units")
_canvas.bind_all("<MouseWheel>",_mw)
def _mw_lnx(e):
    if e.num==4: _canvas.yview_scroll(-1,"units")
    elif e.num==5: _canvas.yview_scroll(1,"units")
_canvas.bind_all("<Button-4>",_mw_lnx); _canvas.bind_all("<Button-5>",_mw_lnx)
cf_=_canvas_frame

# 标题
tk.Label(cf_,text="❤️ 心迹 v3.0",font=("sans-serif",18,"bold"),fg=PK,bg=BG).pack(pady=(14,0))
_hr=tk.Label(cf_,text="--",font=("Consolas",64,"bold"),fg=PK,bg=BG); _hr.pack()
tk.Label(cf_,text="BPM",font=("sans-serif",13),fg="#ffb0bd",bg=BG).pack()

# 状态条
st=tk.Frame(cf_,bg=CD,highlightbackground="#2a2a34",highlightthickness=1,padx=14,pady=8)
st.pack(fill="x",padx=20,pady=(8,0))
_si=tk.Label(st,text="🔴",font=("sans-serif",10),bg=CD); _si.pack(side="left")
_st=tk.Label(st,text="等待连接...",font=("sans-serif",10),fg=DM,bg=CD); _st.pack(side="left",padx=(8,0))
_sd=tk.Label(st,text="",font=("sans-serif",9),fg=DM,bg=CD); _sd.pack(side="right")

# ── 📥 接收推送 ──
recv_frame=tk.LabelFrame(cf_,text="📥 接收推送",font=("sans-serif",10,"bold"),fg="#7a6a70",bg=BG,padx=16,pady=10,border=0)
recv_frame.pack(fill="x",padx=20,pady=(10,0))
recv_inner=tk.Frame(recv_frame,bg=CD,highlightbackground="#2a2a34",highlightthickness=1,padx=12,pady=8)
recv_inner.pack(fill="x")
tk.Label(recv_inner,text=f"本机地址: http://{ip()}:{UNIFIED_PORT}",font=("Consolas",10),fg=PK,bg=CD).pack(anchor="w")
tk.Label(recv_inner,text=f"手机推送: http://{ip()}:{UNIFIED_PORT}/api/hr",font=("Consolas",9),fg="#ffb0bd",bg=CD).pack(anchor="w",pady=(1,0))
tk.Label(recv_inner,text=f"OBS透明: http://{ip()}:{UNIFIED_PORT}/?transparent=1",font=("Consolas",9),fg="#998088",bg=CD).pack(anchor="w",pady=(1,0))
tk.Label(recv_inner,text="手机/手表打开此地址可推送心率到电脑",font=("sans-serif",9),fg=DM,bg=CD).pack(anchor="w",pady=(2,0))
_web_btn=tk.Button(recv_inner,text="🌐 打开页面",font=("sans-serif",8),bg="#2a2a34",fg=PK,relief="flat",padx=8,cursor="hand2")
_web_btn.pack(anchor="e",pady=(2,0))

# ── 📡 ADB 拉取 ──
adb_frame=tk.LabelFrame(cf_,text="📡 ADB 拉取",font=("sans-serif",10,"bold"),fg="#7a6a70",bg=BG,padx=16,pady=10,border=0)
adb_frame.pack(fill="x",padx=20,pady=(10,0))
adb_inner=tk.Frame(adb_frame,bg=CD,highlightbackground="#2a2a34",highlightthickness=1,padx=12,pady=8)
adb_inner.pack(fill="x")
tk.Label(adb_inner,text="设备地址 (IP:端口)",font=("sans-serif",9,"bold"),fg=FG,bg=CD).pack(anchor="w")
adb_ipf=tk.Frame(adb_inner,bg=CD); adb_ipf.pack(fill="x",pady=(4,0))
_adb_ip_var=tk.StringVar(value="192.168.1.5:9090")
_adb_entry=tk.Entry(adb_ipf,textvariable=_adb_ip_var,font=("Consolas",9),bg="#0c0c10",fg=FG,insertbackground=PK,relief="flat",highlightthickness=1,highlightbackground="#2a2a34")
_adb_entry.pack(side="left",fill="x",expand=True,padx=(0,4))
_adb_btn=tk.Button(adb_ipf,text="🔗 连接",font=("sans-serif",9),bg=PK,fg="white",relief="flat",padx=12,cursor="hand2")
_adb_btn.pack(side="left")
_adb_status=tk.Label(adb_inner,text="",font=("sans-serif",8),fg=GN,bg=CD)
_adb_status.pack(anchor="w",pady=(4,0))

# ── BLE 蓝牙连接 ──
ble_frame=tk.LabelFrame(cf_,text="🔵 蓝牙手表",font=("sans-serif",10,"bold"),fg="#7a6a70",bg=BG,padx=16,pady=10,border=0)
ble_frame.pack(fill="x",padx=20,pady=(10,0))
ble_inner=tk.Frame(ble_frame,bg=CD,highlightbackground="#2a2a34",highlightthickness=1,padx=12,pady=8)
ble_inner.pack(fill="x")
_ble_scan_btn=tk.Button(ble_inner,text="🔍 扫描蓝牙设备",font=("sans-serif",9),bg=PK,fg="white",relief="flat",padx=12,cursor="hand2")
_ble_scan_btn.pack(anchor="w")
_ble_list=tk.Listbox(ble_inner,height=4,bg="#0c0c10",fg=FG,selectbackground=PK,selectforeground="white",relief="flat",highlightthickness=1,highlightbackground="#2a2a34",font=("sans-serif",8))
_ble_list.pack(fill="x",pady=(4,0))
_ble_connect_btn=tk.Button(ble_inner,text="🔗 连接选中设备",font=("sans-serif",8),bg="#2a2a34",fg=FG,relief="flat",padx=8,cursor="hand2",state="disabled")
_ble_connect_btn.pack(anchor="w",pady=(2,0))
_ble_disconnect_btn=tk.Button(ble_inner,text="🔌 断开",font=("sans-serif",8),bg="#3a1a1a",fg=PK,relief="flat",padx=8,cursor="hand2",state="disabled")
_ble_disconnect_btn.pack(anchor="w",pady=(2,0))

# ── 📈 HRV 手动记录 ──
hrv_frame=tk.LabelFrame(cf_,text="📈 HRV 手动记录",font=("sans-serif",10,"bold"),fg="#7a6a70",bg=BG,padx=16,pady=10,border=0)
hrv_frame.pack(fill="x",padx=20,pady=(10,0))
hrv_top=tk.Frame(hrv_frame,bg=CD,highlightbackground="#2a2a34",highlightthickness=1,padx=12,pady=8); hrv_top.pack(fill="x")
_hrv_label=tk.Label(hrv_top,text="HRV 记录: 0 条",font=("sans-serif",9),fg=DM,bg=CD); _hrv_label.pack(anchor="w")
hrv_btnf=tk.Frame(hrv_top,bg=CD); hrv_btnf.pack(fill="x",pady=(4,0))
_rec_btn=tk.Button(hrv_btnf,text="⏺ 开始记录",font=("sans-serif",9),bg=PK,fg="white",relief="flat",padx=12,cursor="hand2")
_rec_btn.pack(side="left",padx=(0,4))
_rec_status=tk.Label(hrv_btnf,text="",font=("sans-serif",8),fg=GN,bg=CD); _rec_status.pack(side="left",padx=(4,0))
hrv_cvf=tk.Frame(hrv_frame,bg=CD,highlightbackground="#2a2a34",highlightthickness=1,padx=12,pady=8); hrv_cvf.pack(fill="x",pady=(4,0))
_hrv_canvas=tk.Canvas(hrv_cvf,width=440,height=160,bg="#0c0c10",highlightthickness=0)
_hrv_canvas.pack(padx=8,pady=8)
_hrv_canvas.create_text(220,80,text="点击「开始记录」后连接设备，3分钟以上自动绘制曲线",fill="#998088",font=("sans-serif",9))

# 底部按钮
bt=tk.Frame(cf_,bg=BG); bt.pack(fill="x",padx=20,pady=(12,16))
_settings_btn=tk.Button(bt,text="🎨 设置",font=("sans-serif",10),bg=CD,fg=FG,relief="flat",padx=16,pady=4,cursor="hand2")
_settings_btn.pack(side="left",padx=(0,6))
_float_btn=tk.Button(bt,text="🪟 悬浮窗",font=("sans-serif",10),bg=PK,fg="white",relief="flat",padx=16,pady=4,cursor="hand2")
_float_btn.pack(side="left",padx=(0,6))
_obs_btn=tk.Button(bt,text="📺 OBS 帮助",font=("sans-serif",10),bg="#2a1a1a",fg="#ff5d7c",relief="flat",padx=16,pady=4,cursor="hand2")
_obs_btn.pack(side="left",padx=(0,6))
_pb_btn=tk.Button(bt,text="🛡️ 保活",font=("sans-serif",10),bg=CD,fg=DM,relief="flat",padx=16,pady=4,cursor="hand2")
_pb_btn.pack(side="left",padx=(0,6))
_help_btn=tk.Button(bt,text="❓ 帮助",font=("sans-serif",10),bg=CD,fg="#7a6a70",relief="flat",padx=16,pady=4,cursor="hand2")
_help_btn.pack(side="left",padx=(0,6))
_quit=tk.Button(bt,text="🚪 退出",font=("sans-serif",10),bg="#2a0a0a",fg="#ff5d7c",relief="flat",padx=16,pady=4,cursor="hand2")
_quit.pack(side="right")

# ═════════════════════ 回调绑定 ═════════════════════

def _do_adb_connect():
    if _adb_running:
        stop_adb_pull()
        _adb_btn.config(text="🔗 连接",bg=PK)
        _adb_status.config(text="")
        _adb_entry.config(state="normal")
        return
    target=_adb_ip_var.get().strip()
    if not target: messagebox.showwarning("提示","请输入设备IP:端口"); return
    start_adb_pull(target)
    _adb_btn.config(text="⏹ 断开",bg="#3a1a1a")
    _adb_entry.config(state="readonly")
    _adb_status.config(text=f"🟢 拉取中 ...",fg=GN)

_ble_devices=[]
def _do_ble_scan():
    global _ble_devices
    _ble_list.delete(0,"end")
    _ble_list.insert("end","⏳ 扫描中...")
    def cb(devs):
        _ble_list.delete(0,"end")
        _ble_devices=devs
        if not devs:
            _ble_list.insert("end","⚠️ 未发现蓝牙设备")
            _ble_connect_btn.config(state="disabled")
            return
        for addr, nm, rssi in devs:
            _ble_list.insert("end",f"{nm} ({rssi}dBm)")
        _ble_connect_btn.config(state="normal")
    scan_ble(cb)

def _do_ble_connect():
    sel=_ble_list.curselection()
    if not sel: messagebox.showwarning("提示","请先选择设备"); return
    idx=sel[0]
    if idx>=len(_ble_devices): return
    addr, nm, rssi = _ble_devices[idx]
    connect_ble(addr, nm)
    _ble_connect_btn.config(state="disabled")
    _ble_disconnect_btn.config(state="normal")
    _ble_scan_btn.config(state="disabled")

def _do_ble_disconnect():
    stop_ble()
    _ble_connect_btn.config(state="normal")
    _ble_disconnect_btn.config(state="disabled")
    _ble_scan_btn.config(state="normal")

def _toggle_rec():
    if _recording:
        stop_recording()
        _rec_btn.config(text="⏺ 开始记录",bg=PK)
        _rec_status.config(text="")
    else:
        s = state.snapshot()
        if not s["connected"]:
            messagebox.showwarning("提示","请先连接设备")
            return
        if not start_recording():
            messagebox.showwarning("提示","未收到心率数据，请确认设备已连接并输出心率")
        _rec_btn.config(text="⏹ 停止记录",bg="#3a1a1a")
        _rec_status.config(text="🔴 记录中...",fg=PK)

def _show_help():
    messagebox.showinfo("❓ 帮助",
        "❤️ 心迹 v3.0 — 电脑端\n\n"
        "━━━ 连接方式 ━━━\n"
        "1. 蓝牙手表：扫描→选择→连接\n"
        "2. ADB 拉取：输入手机IP:端口→连接\n"
        "3. 接收推送：手机打开本机地址推送\n\n"
        "━━━ HRV 记录 ━━━\n"
        "点击「开始记录」，3分钟以上自动绘制曲线\n"
        "注意：需先连接心率设备，且收到数据才能开始\n\n"
        "━━━ 悬浮窗 ━━━\n"
        "迷你窗口置顶显示心率，打游戏也能看\n"
        "右键悬浮窗可切换样式 / 透明度 / 字号\n"
        "位置会自动记忆，下次打开时恢复\n\n"
        "━━━ OBS 直播 ━━━\n"
        "见「📺 OBS 帮助」按钮")

def _show_obs_help():
    obs_url = f"http://{ip()}:{UNIFIED_PORT}"
    obs_url_transparent = f"http://{ip()}:{UNIFIED_PORT}?transparent=1"
    messagebox.showinfo("📺 OBS 直播配置",
        f"━━━ 心迹 + OBS 直播配置 ━━━\n\n"
        f"方法一：浏览器源（推荐）\n"
        f"1. 启动本程序\n"
        f"2. OBS → 来源 → + → 浏览器\n"
        f"3. URL 输入 {obs_url}\n"
        f"4. 宽度/高度：建议 200x120\n"
        f"5. 勾选「通过OBS控制音频」→ 否\n\n"
        f"透明模式：URL 加上 ?transparent=1\n"
        f"→ {obs_url_transparent}\n\n"
        f"方法二：窗口捕获\n"
        f"1. 先打开悬浮窗\n"
        f"2. OBS → 来源 → + → 窗口捕获\n"
        f"3. 选择「心迹悬浮窗」窗口\n"
        f"4. 用「色度键」滤镜去掉背景\n\n"
        f"━━━ 注意事项 ━━━\n"
        f"• 手机和电脑需要在同一局域网\n"
        f"• 可用手机APP扫码或手动输入地址推送心率\n"
        f"• 心率数值仅供娱乐参考，不作医疗用途")

def _show_settings(e=None):
    m=tk.Menu(root,tearoff=0,bg="#1a1a24",fg="#e2c2cf",activebackground="#ff5d7c",activeforeground="#fff")
    m.add_command(label="🎨 背景色",command=lambda:_pc("bg"))
    m.add_command(label="✏️ 文字色",command=lambda:_pc("fg"))
    m.add_command(label="📏 字号",command=_pfs)
    m.add_command(label="🔲 透明度",command=_ppo)
    try: m.tk_popup(root.winfo_x()+20,root.winfo_y()+60)
    finally: m.grab_release()

def _pc(k):
    c=colorchooser.askcolor(title="选择颜色",color=CFG[k])
    if c and c[1]: CFG[k]=c[1]
def _pfs():
    s=simpledialog.askinteger("字号","12-80:",initialvalue=CFG["fs"],minvalue=12,maxvalue=80)
    if s: CFG["fs"]=s
def _ppo():
    s=simpledialog.askfloat("透明度","0.1-1.0:",initialvalue=CFG["op"],minvalue=0.1,maxvalue=1.0)
    if s: CFG["op"]=s

def _upd():
    s = state.snapshot()
    connected = s["connected"]
    hr_val = s["hr"]
    _hr.config(text=str(hr_val) if connected and hr_val > 30 else "--",
               font=("Consolas", CFG["fs"], "bold"))
    if connected and hr_val > 30:
        if hr_val < 70: _hr.config(fg=GN)
        elif hr_val < 100: _hr.config(fg=CFG["fg"])
        elif hr_val < 140: _hr.config(fg="#ff3d00")
        else: _hr.config(fg="#d50000")
    else: _hr.config(fg=CFG["fg"])
    root.attributes("-alpha", CFG["op"])
    if connected:
        _si.config(text="🟢")
        _st.config(text=s["device"], fg=FG)
        _sd.config(text=s["type"] or "")
    else:
        _si.config(text="🔴")
        _st.config(text="等待连接...", fg=DM)
        _sd.config(text="")
    if _adb_running:
        _adb_status.config(text=f"🟢 拉取中 · {_adb_device}", fg=GN)
    if _recording:
        dur = time.time() - _rec_t0
        _rec_status.config(text=f"🔴 记录中 {int(dur//60)}:{int(dur%60):02d}", fg=PK)
    root.after(500, _upd)

# 绑定按钮
_adb_btn.config(command=_do_adb_connect)
_ble_scan_btn.config(command=_do_ble_scan)
_ble_connect_btn.config(command=_do_ble_connect)
_ble_disconnect_btn.config(command=_do_ble_disconnect)
_rec_btn.config(command=_toggle_rec)
_help_btn.config(command=_show_help)
_obs_btn.config(command=_show_obs_help)
_pb_btn.config(command=_toggle_protect)
_settings_btn.config(command=_show_settings)
_float_btn.config(command=_toggle_float)
_web_btn.config(command=lambda: webbrowser.open(f"http://127.0.0.1:{UNIFIED_PORT}"))

# 退出时安全终止线程
def _on_close():
    if _adb_running: stop_adb_pull()
    if _ble_connected: stop_ble()
    if _float_showing: _close_float()
    root.destroy()
_quit.config(command=_on_close)
root.protocol("WM_DELETE_WINDOW", _on_close)

# ═════════════════════ 启动 ═════════════════════

_start_server()
s = state.snapshot()
print(f"\n❤️ 心迹 v3.0 — 电脑端")
print(f"📥 接收推送: http://{ip()}:{UNIFIED_PORT}")
print(f"🌐 网页心率: http://{ip()}:{UNIFIED_PORT}")
print(f"🔵 蓝牙扫描: 支持 Polar H10 / 小米手环等 BLE 心率设备")
print(f"📡 ADB 拉取: 支持局域网拉取手机心率")
print(f"📈 HRV 记录: 点击「开始记录」3分钟以上自动绘制")
print(f"📺 OBS 直播: 浏览器源添加 http://{ip()}:{UNIFIED_PORT}?transparent=1")
root.after(1000, _upd)
root.mainloop()