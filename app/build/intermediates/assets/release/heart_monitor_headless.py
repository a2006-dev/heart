# -*- coding: utf-8 -*-
"""
❤️ 心迹 v3.0 — 无头静默版（无需桌面环境，纯 Web 浏览器操作）
保存为 .pyw 无控制台运行，所有输出写入日志文件
在云电脑/服务器上直接运行，通过浏览器访问 http://本机IP:9090
"""
import json, threading, time, webbrowser, socket, os, sys, logging
from http.server import HTTPServer, BaseHTTPRequestHandler
from dataclasses import dataclass, field
from typing import List

# ═════════════════════ 日志配置（替代 print） ═════════════════════
_LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "heart_monitor.log") if "__file__" in dir() else "heart_monitor.log"
logging.basicConfig(
    filename=_LOG_FILE,
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
    encoding="utf-8"
)

_DEMO = False
UNIFIED_PORT = 9090
SRC_BLE, SRC_ADB, SRC_PUSH = 3, 2, 1

@dataclass
class HeartState:
    hr: int = 0
    connected: bool = False
    device_name: str = ""
    conn_type: str = "未连接"
    source_priority: int = 0
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def update(self, v: int, name: str = "", ctype: str = "", priority: int = 0):
        with self._lock:
            if not (0 < v < 250): return
            if priority < self.source_priority and self.connected: return
            self.hr, self.connected, self.source_priority = v, True, priority
            if name: self.device_name = name
            if ctype: self.conn_type = ctype

    def snapshot(self) -> dict:
        with self._lock:
            return {"hr": self.hr, "connected": self.connected,
                    "device": self.device_name, "type": self.conn_type}

state = HeartState()
LOGS, _IP = [], None

def log(m):
    LOGS.append(m)
    if len(LOGS) > 30: LOGS.pop(0)
    logging.info(m)

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
            if a.startswith("192.168.") or a.startswith("10."): _IP = a; break
    if not _IP: _IP = "127.0.0.1"
    return _IP

def find_free_port(base: int, max_try: int = 10) -> int:
    for i in range(max_try):
        p = base + i
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(1); s.bind(("0.0.0.0", p)); s.close()
            return p
        except: continue
    return base

HTML_PAGE = '''<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>❤️ 心迹 - 电脑端</title>
<style>
*{margin:0;padding:0;box-sizing:border-box;}
body{background:#0c0c10;color:#e2c2cf;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;min-height:100vh;padding:20px;}
.container{max-width:520px;width:100%;}
.card{background:#1a1a24;border-radius:16px;padding:24px;margin-bottom:16px;border:1px solid #2a2a34;}
h1{text-align:center;color:#ff5d7c;font-size:28px;margin:10px 0;}
.hr-display{text-align:center;padding:20px 0;}
.hr-number{font-size:80px;font-weight:bold;font-family:monospace;color:#ff5d7c;line-height:1;}
.hr-unit{font-size:16px;color:#ffb0bd;}
.status-bar{display:flex;align-items:center;gap:8px;padding:10px 16px;background:#0c0c10;border-radius:10px;margin:8px 0;}
.dot{width:10px;height:10px;border-radius:50%;}
.dot.green{background:#4CAF50;box-shadow:0 0 8px #4CAF50;}
.dot.red{background:#f44b6e;}
.info{color:#998088;font-size:13px;line-height:1.6;}
.info a{color:#ff5d7c;text-decoration:none;}
.info a:hover{text-decoration:underline;}
.code{background:#0c0c10;padding:8px 12px;border-radius:8px;font-family:monospace;font-size:13px;color:#ff5d7c;word-break:break-all;}
.section-title{font-size:14px;color:#7a6a70;margin-bottom:10px;font-weight:bold;}
.footer{text-align:center;color:#665c62;font-size:12px;padding:20px 0;}
</style></head>
<body>
<div class="container">
<h1>❤️ 心迹 v3.0</h1>
<div class="card">
<div class="hr-display">
<div class="hr-number" id="hrVal">--</div>
<div class="hr-unit">BPM</div>
</div>
<div class="status-bar" id="statusBar">
<span class="dot red" id="statusDot"></span>
<span id="statusText">等待连接...</span>
<span style="margin-left:auto;color:#998088;font-size:12px;" id="connType"></span>
</div>
</div>

<div class="card">
<div class="section-title">📥 接收推送</div>
<div class="code" id="pushUrl">加载中...</div>
<div class="info" style="margin-top:8px;">手机/手表打开此地址可推送心率到电脑</div>
</div>

<div class="card">
<div class="section-title">📺 OBS 直播</div>
<div class="code" id="obsUrl">加载中...</div>
<div class="info" style="margin-top:8px;">浏览器源添加此链接即可在直播中显示心率</div>
</div>

<div class="card">
<div class="section-title">📡 状态信息</div>
<div class="info" id="logArea">等待心跳数据...</div>
</div>

<div class="footer">心迹 v3.0 — 电脑端心率监测</div>
</div>
<script>
function updateDisplay(d){
document.getElementById('hrVal').innerText = (d.connected && d.hr>30 && d.hr<220) ? d.hr : '--';
document.getElementById('statusText').innerText = (d.connected && d.hr>30) ? (d.device || '已连接') : '等待连接...';
document.getElementById('connType').innerText = d.type || '';
var dot = document.getElementById('statusDot');
dot.className = 'dot ' + ((d.connected && d.hr>30) ? 'green' : 'red');
}
function fetchHR(){fetch('/api/hr').then(r=>r.json()).then(updateDisplay).catch(function(){});}
setInterval(fetchHR, 1000);fetchHR();
var es=new EventSource('/stream');
es.onmessage=function(e){try{updateDisplay(JSON.parse(e.data));}catch(err){}};
fetch('/api/info').then(r=>r.json()).then(d=>{
document.getElementById('pushUrl').innerText = 'http://'+d.ip+':'+d.port+'/api/hr';
document.getElementById('obsUrl').innerText = 'http://'+d.ip+':'+d.port+'/?transparent=1';
document.title = '❤️ 心迹 - '+d.ip+':'+d.port;
});
</script>
</body></html>'''

def start_server():
    global UNIFIED_PORT
    UNIFIED_PORT = find_free_port(UNIFIED_PORT)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/stream": return self._sse()
            elif path == "/api/hr": return self._json()
            elif path == "/api/info": return self._info()
            else: return self._page()
        def do_POST(self):
            if self.path == "/api/hr": return self._push()
            self.send_response(404); self.end_headers()
        def _page(self):
            self.send_response(200)
            self.send_header("Content-Type","text/html;charset=utf-8")
            self.send_header("Cache-Control","no-cache"); self.send_header("Access-Control-Allow-Origin","*")
            self.end_headers(); self.wfile.write(HTML_PAGE.encode("utf-8"))
        def _json(self):
            s=state.snapshot(); self.send_response(200)
            self.send_header("Content-Type","application/json;charset=utf-8")
            self.send_header("Access-Control-Allow-Origin","*"); self.end_headers()
            self.wfile.write(json.dumps(s).encode("utf-8"))
        def _info(self):
            d=json.dumps({"ip":ip(),"port":UNIFIED_PORT}); self.send_response(200)
            self.send_header("Content-Type","application/json;charset=utf-8")
            self.send_header("Access-Control-Allow-Origin","*"); self.end_headers()
            self.wfile.write(d.encode("utf-8"))
        def _sse(self):
            self.send_response(200)
            for k,v in {"Content-Type":"text/event-stream","Cache-Control":"no-cache",
                        "Connection":"keep-alive","Access-Control-Allow-Origin":"*"}.items():
                self.send_header(k,v)
            self.end_headers()
            try:
                while True:
                    self.wfile.write(f"data: {json.dumps(state.snapshot())}\n\n".encode("utf-8"))
                    self.wfile.flush(); time.sleep(0.5)
            except: pass
        def _push(self):
            l=int(self.headers.get("Content-Length",0))
            if l>0:
                try:
                    b=self.rfile.read(l).decode("utf-8"); d=json.loads(b); v=d.get("hr",0)
                    if isinstance(v,(int,float)) and 0<v<250:
                        state.update(int(v),d.get("device","手机"),d.get("connect_type","WiFi"),priority=1)
                except: pass
            self.send_response(200); self.send_header("Access-Control-Allow-Origin","*"); self.end_headers()
        def log_message(self,*a): pass

    server = HTTPServer(("0.0.0.0", UNIFIED_PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log(f"🌐 服务器启动: http://{ip()}:{UNIFIED_PORT}")
    log(f"📱 推送地址: http://{ip()}:{UNIFIED_PORT}/api/hr")
    log(f"🎬 OBS: http://{ip()}:{UNIFIED_PORT}/?transparent=1")

# ═════════════════════ 启动 ═════════════════════
log("❤️ 心迹 v3.0 无头静默版")
log(f"📥 浏览器打开: http://{ip()}:{UNIFIED_PORT}")
start_server()
log(f"✅ 服务已启动！浏览器访问: http://{ip()}:{UNIFIED_PORT}")
log(f"📱 手机推送: http://{ip()}:{UNIFIED_PORT}/api/hr")
log(f"📺 OBS 透明: http://{ip()}:{UNIFIED_PORT}/?transparent=1")

try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    log("👋 服务已停止")