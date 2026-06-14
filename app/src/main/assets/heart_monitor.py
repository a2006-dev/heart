# -*- coding: utf-8 -*-
"""
❤️ 心迹 v3.2 — 悬浮窗 + HRV + MQTT
双击运行，支持桌面悬浮窗、HRV记录、MQTT远程、OBS叠加
"""
import json, threading, time, socket, os, sys, subprocess, importlib, urllib.request, logging, webbrowser
from http.server import HTTPServer, BaseHTTPRequestHandler
from dataclasses import dataclass, field
from typing import List, Optional

_LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "heart_monitor.log") if "__file__" in dir() else "heart_monitor.log"
logging.basicConfig(filename=_LOG_FILE, level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S", encoding="utf-8")

_HAS_GUI = False
try:
    import tkinter as tk
    from tkinter import ttk, scrolledtext, messagebox, simpledialog
    _HAS_GUI = True
except: pass

UNIFIED_PORT = 9090; SRC_MQTT = 2

@dataclass
class HeartState:
    hr: int = 0; connected: bool = False; device_name: str = ""
    conn_type: str = "未连接"; source_priority: int = 0
    _lock: threading.Lock = field(default_factory=threading.Lock)
    def update(self, v, name="", ctype="", priority=0):
        with self._lock:
            if not (0 < v < 250): return
            if priority < self.source_priority and self.connected: return
            self.hr, self.connected, self.source_priority = v, True, priority
            if name: self.device_name = name; self.conn_type = ctype
    def snapshot(self):
        with self._lock: return {"hr":self.hr,"connected":self.connected,"device":self.device_name,"type":self.conn_type}

state = HeartState(); LOGS = []; _IP = None

def ip():
    global _IP
    if _IP: return _IP
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(1)
        s.connect(("8.8.8.8",80)); _IP = s.getsockname()[0]; s.close()
    except:
        for i in socket.getaddrinfo(socket.gethostname(),None):
            a=i[4][0]
            if a.startswith("192.168.") or a.startswith("10."): _IP=a; break
    if not _IP: _IP="127.0.0.1"
    return _IP

def log(m):
    LOGS.append(m)
    if len(LOGS)>50: LOGS.pop(0)
    logging.info(m)

# ═══════════ MQTT ═══════════
_mqtt_client=None; _mqtt_connected=False; _mqtt_thread=None

def _ensure_paho():
    try: import paho.mqtt.client as mqtt; return True
    except ImportError:
        log("📦 安装 paho-mqtt...")
        try:
            subprocess.check_call([sys.executable,"-m","pip","install","paho-mqtt","-q","-i","https://pypi.tuna.tsinghua.edu.cn/simple"],timeout=60)
            import paho.mqtt.client as mqtt; log("✅ 完成"); return True
        except: log("❌ 失败，运行: pip install paho-mqtt"); return False

def parse_conn_code(code):
    if not code.startswith("HEARTBEAT#"): return None
    body=code[10:]; parts=body.split("#",3)
    if len(parts)<4 or parts[0]!="V1": return None
    tag,hp,tb=parts[1],parts[2],parts[3]; i=hp.rfind(":")
    if i<=0: return None
    try: p=int(hp[i+1:])
    except: p=1883
    ft=tb
    if not ft.endswith("/"+tag): ft+="/"+tag
    return {"host":hp[:i],"port":p,"topic":ft,"tag":tag}

def mqtt_cb(client,userdata,msg):
    try:
        d=json.loads(msg.payload.decode()); hr=d.get("hr",0)
        if isinstance(hr,(int,float)) and 20<hr<250:
            state.update(int(hr),d.get("device","MQTT"),"MQTT",priority=SRC_MQTT)
    except: pass

def _mqtt_loop(broker,port,topic):
    global _mqtt_client,_mqtt_connected
    try:
        import paho.mqtt.client as mqtt
        c=mqtt.Client(client_id=f"hr_{int(time.time())}",protocol=mqtt.MQTTv311)
        c.on_message=mqtt_cb; log(f"🔗 MQTT {broker}:{port}..."); c.connect(broker,port,30)
        c.subscribe(topic); log(f"✅ {topic}")
        wt=topic; i=wt.rfind("/")
        if i>=0: wt=wt[:i]+"/+"; c.subscribe(wt); log(f"✅ {wt}")
        _mqtt_client=c;_mqtt_connected=True; c.loop_forever()
    except Exception as e:
        log(f"❌ MQTT: {e}"); _mqtt_connected=False;_mqtt_client=None

def mqtt_connect(code):
    global _mqtt_thread,_mqtt_client,_mqtt_connected
    if _mqtt_connected: mqtt_disconnect()
    p=parse_conn_code(code)
    if not p: return "❌ 连接码无效"
    if not _ensure_paho(): return "❌ paho-mqtt 未安装"
    _mqtt_thread=threading.Thread(target=_mqtt_loop,args=(p["host"],p["port"],p["topic"]),daemon=True); _mqtt_thread.start()
    return f"✅ 连接 {p['host']}:{p['port']}..."

def mqtt_disconnect():
    global _mqtt_client,_mqtt_connected
    if _mqtt_client:
        try: _mqtt_client.disconnect()
        except: pass
        _mqtt_client=None
    _mqtt_connected=False; log("🔌 MQTT 断开")

def test_mqtt(code):
    p=parse_conn_code(code)
    if not p: return "❌ 无效连接码"
    try:
        s=socket.socket();s.settimeout(5);s.connect((p["host"],p["port"]));s.close()
        return "✅ Broker 可达！收不到数据用 mqtt.html 诊断"
    except Exception as e: return f"❌ {e}"

# ═══════════ 局域网拉取 ═══════════
SRC_LAN = 1
_adb_running = False; _adb_thread = None; _adb_stop = threading.Event()

def start_lan_pull(device_addr):
    global _adb_running, _adb_thread, _adb_stop
    if _adb_running: stop_lan_pull()
    _adb_stop.clear(); _adb_running = True
    _adb_thread = threading.Thread(target=_lan_loop, args=(device_addr,), daemon=True)
    _adb_thread.start()
    log(f"📡 连接手机: {device_addr}")

def stop_lan_pull():
    global _adb_running, _adb_thread
    _adb_running = False; _adb_stop.set(); _adb_thread = None

def _lan_loop(addr):
    fail = 0
    while _adb_running and not _adb_stop.is_set():
        try:
            url = f"http://{addr}/api/hr"
            resp = urllib.request.urlopen(url, timeout=3)
            d = json.loads(resp.read().decode())
            v = d.get("hr", 0)
            if isinstance(v, (int, float)) and 0 < v < 250:
                state.update(int(v), d.get("device", "手机"), "局域网", priority=SRC_LAN)
                fail = 0
            resp.close()
        except:
            fail += 1
            if fail == 1: log(f"⚠️ 连接异常({addr})，重试中...")
        _adb_stop.wait(2 if fail < 10 else 5)

def scan_lan():
    """扫描局域网内的心迹设备"""
    found = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(1)
        s.connect(("8.8.8.8", 80)); local_ip = s.getsockname()[0]; s.close()
        parts = local_ip.split(".")
        subnet = f"{parts[0]}.{parts[1]}.{parts[2]}."
    except:
        log("❌ 无法获取局域网网段")
        return found
    def check(ip):
        try:
            url = f"http://{ip}:9090/api/hr"
            resp = urllib.request.urlopen(url, timeout=1)
            data = json.loads(resp.read().decode())
            resp.close()
            if data.get("hr", 0) >= 0:
                return data.get("device", "手机")
        except: pass
        return None
    log(f"🔍 扫描 {subnet}0/24 ...")
    for i in range(1, 255):
        ip = subnet + str(i)
        if ip == local_ip: continue
        name = check(ip)
        if name:
            found.append((ip, name))
            log(f"  📱 {name} → {ip}:9090")
    if found:
        log(f"✅ 发现 {len(found)} 台设备")
    else:
        log("📭 未发现设备，请确认手机已开启广播")
    return found
def find_free_port(base,m=10):
    for i in range(m):
        try:
            s=socket.socket();s.settimeout(1);s.bind(("0.0.0.0",base+i));s.close();return base+i
        except: continue
    return base

def _start_server():
    global UNIFIED_PORT; UNIFIED_PORT=find_free_port(UNIFIED_PORT)
    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            p=self.path.split("?")[0]
            if p=="/stream":self._sse()
            elif p=="/api/hr":self._json()
            else:self._page()
        def do_POST(self):
            p=self.path.split("?")[0]
            if p=="/api/hr":self._push()
            elif p=="/api/mqtt/connect":self._mc()
            elif p=="/api/mqtt/disconnect":self._md()
            elif p=="/api/mqtt/test":self._mt()
            else:self.send_response(404);self.end_headers()
        def _page(self):
            self.send_response(200)
            self.send_header("Content-Type","text/html;charset=utf-8");self.send_header("Cache-Control","no-cache");self.send_header("Access-Control-Allow-Origin","*")
            self.end_headers()
            self.wfile.write(f"""<!DOCTYPE html><html><head><meta charset="UTF-8"><title>心迹</title><style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{background:transparent;font-family:sans-serif;overflow:hidden}}
#fw{{position:fixed;top:20px;right:20px;display:flex;flex-direction:column;align-items:center;gap:4px;padding:12px 18px;background:rgba(12,12,16,0.75);border-radius:16px;border:1px solid rgba(255,93,124,0.25);user-select:none}}
#hrV{{font-size:3rem;font-weight:700;color:#ff5d7c;text-shadow:0 0 14px rgba(255,60,100,0.5);line-height:1}}
.bpm{{font-size:0.8rem;color:#ffb0bd}}
#devN{{font-size:0.6rem;color:#998088;text-align:center}}
</style></head><body>
<div id="fw"><div><span id="hrV">--</span><span class="bpm"> BPM</span></div><div><span id="devN">等待连接...</span></div></div>
<script>
var es=new EventSource('/stream');
es.onmessage=function(e){{try{{var d=JSON.parse(e.data);var el=document.getElementById('hrV'),dn=document.getElementById('devN');
if(d.connected&&d.hr>30&&d.hr<220){{el.innerText=d.hr;dn.innerText=d.device||'已连接'}}
else{{el.innerText='--';dn.innerText='等待连接...'}}}}catch(err){{}}}}
</script></body></html>""".encode())
        def _json(self):
            s=state.snapshot();self.send_response(200)
            self.send_header("Content-Type","application/json;charset=utf-8");self.send_header("Access-Control-Allow-Origin","*");self.end_headers()
            self.wfile.write(json.dumps(s).encode())
        def _sse(self):
            self.send_response(200)
            for k,v in {"Content-Type":"text/event-stream","Cache-Control":"no-cache","Connection":"keep-alive","Access-Control-Allow-Origin":"*"}.items():self.send_header(k,v)
            self.end_headers()
            try:
                while True:
                    self.wfile.write(f"data: {json.dumps(state.snapshot())}\n\n".encode());self.wfile.flush();time.sleep(0.5)
            except: pass
        def _push(self):
            l=int(self.headers.get("Content-Length",0))
            if l>0:
                try:
                    b=self.rfile.read(l).decode();d=json.loads(b);v=d.get("hr",0)
                    if isinstance(v,(int,float)) and 0<v<250:
                        state.update(int(v),d.get("device","手机"),d.get("connect_type","WiFi"),priority=1)
                except: pass
            self.send_response(200);self.send_header("Access-Control-Allow-Origin","*");self.end_headers()
        def _mc(self):
            l=int(self.headers.get("Content-Length",0));c=""
            if l>0:
                try: c=self.rfile.read(l).decode()
                except: pass
            msg=mqtt_connect(c) if c else "❌ 无连接码"
            self.send_response(200);self.send_header("Content-Type","application/json;charset=utf-8");self.send_header("Access-Control-Allow-Origin","*");self.end_headers()
            self.wfile.write(json.dumps({"msg":msg}).encode())
        def _md(self):
            mqtt_disconnect()
            self.send_response(200);self.send_header("Content-Type","application/json;charset=utf-8");self.send_header("Access-Control-Allow-Origin","*");self.end_headers()
            self.wfile.write(json.dumps({"msg":"已断开"}).encode())
        def _mt(self):
            l=int(self.headers.get("Content-Length",0));c=""
            if l>0:
                try: c=self.rfile.read(l).decode()
                except: pass
            msg=test_mqtt(c) if c else "❌ 无连接码"
            self.send_response(200);self.send_header("Content-Type","application/json;charset=utf-8");self.send_header("Access-Control-Allow-Origin","*");self.end_headers()
            self.wfile.write(json.dumps({"msg":msg}).encode())
        def log_message(self,*a): pass
    server=HTTPServer(("0.0.0.0",UNIFIED_PORT),H)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    log(f"🌐 http://{ip()}:{UNIFIED_PORT}  🎬OBS: http://{ip()}:{UNIFIED_PORT}/?transparent=1")

# ═══════════ GUI + 悬浮窗 + HRV ═══════════
if _HAS_GUI:
    _rec_data=[]; _recording=False; _rec_start=0.0
    _float_win=None; _float_label=None; _float_showing=False; _float_locked=False
    _FLOAT_STYLES=[
        {"name":"默认暗红","bg":"#0c0c10","fg":"#ff5d7c","border":"#2a2a34"},
        {"name":"极简黑","bg":"#000000","fg":"#ffffff","border":"#333333"},
        {"name":"霓虹绿","bg":"#001000","fg":"#00ff41","border":"#00ff41"},
        {"name":"赛博蓝","bg":"#000d1a","fg":"#00bfff","border":"#00bfff"},
        {"name":"暖橙","bg":"#1a0d00","fg":"#ff8c00","border":"#ff8c00"},
        {"name":"透明红","bg":"#1a0000","fg":"#ff2d55","border":"#ff2d55"},
        {"name":"高对比白","bg":"#111111","fg":"#ffffff","border":"#ff5d7c"},
    ]
    _float_sidx=0

    class App:
        def __init__(self):
            self.root=tk.Tk(); self.root.title("❤️ 心迹 v3.2")
            self.root.geometry("520x820"); self.root.configure(bg='#1a1a24')
            self.root.resizable(False,False)
            s=ttk.Style();s.theme_use('clam')
            s.configure('TLabel',background='#1a1a24',foreground='#e2c2cf',font=('Segoe UI',10))

            f=self.root
            self.hr_label=tk.Label(self.root,text="--",font=('Consolas',72,'bold'),fg='#ff5d7c',bg='#1a1a24')
            self.hr_label.pack(pady=(15,0))
            tk.Label(self.root,text="BPM",font=('Segoe UI',14),fg='#ffb0bd',bg='#1a1a24').pack()
            self.status_label=tk.Label(self.root,text="⏳ 等待连接...",font=('Segoe UI',12),fg='#998088',bg='#1a1a24')
            self.status_label.pack(pady=(3,0))
            self.device_label=tk.Label(self.root,text="未连接",font=('Segoe UI',10),fg='#7a6a70',bg='#1a1a24')
            self.device_label.pack()
            tk.Frame(self.root,height=1,bg='#2a2a34').pack(fill='x',padx=30,pady=8)

            # MQTT
            mf=tk.Frame(self.root,bg='#1a1a24');mf.pack(padx=25,pady=3,fill='x')
            tk.Label(mf,text="📡 MQTT 连接码",font=('Segoe UI',11,'bold'),fg='#7a6a70',bg='#1a1a24').pack(anchor='w')
            self.code_entry=tk.Entry(mf,font=('Consolas',10),bg='#0c0c10',fg='#ffb0bd',insertbackground='#ffb0bd',relief='flat',bd=8)
            self.code_entry.pack(fill='x',pady=(3,5));self.code_entry.insert(0,"粘贴连接码...")
            self.code_entry.bind('<FocusIn>',lambda e: self.code_entry.delete(0,'end') if self.code_entry.get()=="粘贴连接码..." else None)
            bf=tk.Frame(mf,bg='#1a1a24');bf.pack(fill='x')
            for t,c in [("🔗 连接",self.mqtt_con),("⏹ 断开",self.mqtt_dis),("🔌 测试",self.mqtt_test)]:
                bgc='#ff5d7c' if t=="🔗 连接" else '#665c62'
                tk.Button(bf,text=t,command=c,bg=bgc,fg='white',relief='flat',padx=10,cursor='hand2',font=('Segoe UI',9)).pack(side='left',padx=2)
            tk.Button(bf,text="❓ 帮助",command=self.show_help,bg='#3a3a44',fg='#ccc',relief='flat',padx=10,cursor='hand2',font=('Segoe UI',9)).pack(side='right')
            self.mqtt_status=tk.Label(mf,text="未连接",font=('Segoe UI',9),fg='#998088',bg='#1a1a24')
            self.mqtt_status.pack(anchor='w',pady=(3,0))

            # 局域网区域
            tk.Frame(self.root,height=1,bg='#2a2a34').pack(fill='x',padx=30,pady=5)
            lf=tk.Frame(self.root,bg='#1a1a24');lf.pack(padx=25,pady=3,fill='x')
            tk.Label(lf,text="📶 局域网 / USB",font=('Segoe UI',11,'bold'),fg='#7a6a70',bg='#1a1a24').pack(anchor='w')
            ip_frame=tk.Frame(lf,bg='#1a1a24');ip_frame.pack(fill='x',pady=(3,3))
            self.lan_entry=tk.Entry(ip_frame,font=('Consolas',10),bg='#0c0c10',fg='#ffb0bd',insertbackground='#ffb0bd',relief='flat',bd=8)
            self.lan_entry.pack(side='left',fill='x',expand=True)
            self.lan_entry.insert(0,"192.168.1.5:9090")
            tk.Button(ip_frame,text="连接",command=self.lan_con,bg='#3a3a44',fg='#ccc',relief='flat',padx=8,font=('Segoe UI',9),cursor='hand2').pack(side='right',padx=(4,0))
            bf_lan=tk.Frame(lf,bg='#1a1a24');bf_lan.pack(fill='x')
            tk.Button(bf_lan,text="🔍 扫描局域网",command=self.lan_scan,bg='#3a3a44',fg='#ccc',relief='flat',padx=10,font=('Segoe UI',9),cursor='hand2').pack(side='left',padx=2)
            tk.Button(bf_lan,text="⏹ 断开",command=self.lan_dis,bg='#665c62',fg='#ccc',relief='flat',padx=10,font=('Segoe UI',9),cursor='hand2').pack(side='left',padx=2)
            self.lan_status=tk.Label(lf,text="未连接",font=('Segoe UI',9),fg='#998088',bg='#1a1a24')
            self.lan_status.pack(anchor='w',pady=(2,0))

            # 功能按钮
            tk.Frame(self.root,height=1,bg='#2a2a34').pack(fill='x',padx=30,pady=8)
            bf2=tk.Frame(self.root,bg='#1a1a24');bf2.pack(padx=25,pady=3,fill='x')
            tk.Button(bf2,text="🪟 悬浮窗",command=self.toggle_float,bg='#ff5d7c',fg='white',relief='flat',padx=12,cursor='hand2').pack(side='left',padx=2)
            tk.Button(bf2,text="⏺ HRV 记录",command=self.toggle_hrv,bg='#3a3a44',fg='#ccc',relief='flat',padx=12,cursor='hand2').pack(side='left',padx=2)

            # 地址信息 + 打开按钮
            info_frame=tk.Frame(self.root,bg='#1a1a24');info_frame.pack(padx=25,pady=(5,0),fill='x')
            row1=tk.Frame(info_frame,bg='#1a1a24');row1.pack(fill='x',pady=1)
            tk.Label(row1,text=f"🌐 http://{ip()}:{UNIFIED_PORT}",font=('Consolas',9),fg='#ff5d7c',bg='#1a1a24').pack(side='left')
            tk.Button(row1,text="打开",command=lambda: webbrowser.open(f"http://{ip()}:{UNIFIED_PORT}"),bg='#3a3a44',fg='#ccc',relief='flat',padx=8,font=('Segoe UI',8),cursor='hand2').pack(side='right')
            row2=tk.Frame(info_frame,bg='#1a1a24');row2.pack(fill='x',pady=1)
            tk.Label(row2,text=f"🎬 OBS: http://{ip()}:{UNIFIED_PORT}",font=('Consolas',9),fg='#998088',bg='#1a1a24').pack(side='left')
            tk.Button(row2,text="打开",command=lambda: webbrowser.open(f"http://{ip()}:{UNIFIED_PORT}"),bg='#3a3a44',fg='#ccc',relief='flat',padx=8,font=('Segoe UI',8),cursor='hand2').pack(side='right')

            # HRV
            self.hrv_frame=tk.Frame(self.root,bg='#1a1a24')
            self.hrv_frame.pack(padx=25,pady=(5,0),fill='x')
            self.hrv_label=tk.Label(self.hrv_frame,text="📈 HRV: 未记录",font=('Segoe UI',9),fg='#7a6a70',bg='#1a1a24')
            self.hrv_label.pack(anchor='w')
            self.hrv_canvas=tk.Canvas(self.hrv_frame,height=100,bg='#0c0c10',highlightthickness=1,highlightbackground='#2a2a34')
            self.hrv_canvas.pack(fill='x',pady=(3,0))
            self.hrv_canvas.create_text(200,40,text="点击「HRV 记录」开始",fill='#3a3438',font=('Consolas',10))

            # 日志
            self.log_area=scrolledtext.ScrolledText(self.root,height=3,font=('Consolas',9),bg='#0c0c10',fg='#0f0',insertbackground='#0f0',relief='flat',bd=8)
            self.log_area.pack(padx=20,pady=(5,12),fill='both',expand=True)
            self.update_hr();self.update_log();self.update_hrv()

        def update_hr(self):
            s=state.snapshot()
            if s['connected'] and 30<s['hr']<220:
                self.hr_label.config(text=str(s['hr']),fg='#ff5d7c')
                self.status_label.config(text=f"✅ {s['device']}")
                if _recording: _rec_data.append((time.time(),s['hr']))
            else:
                self.hr_label.config(text='--',fg='#443a40')
                self.status_label.config(text='⏳ 等待连接...')
            self.device_label.config(text=s['type'] if s['type']!="未连接" else "未连接")
            if _float_win and _float_label:
                txt="--" if not(s['connected'] and 30<s['hr']<220) else str(s['hr'])
                _float_label.config(text=txt)
            self.root.after(500,self.update_hr)

        def update_log(self):
            if LOGS:
                self.log_area.delete('1.0','end')
                self.log_area.insert('1.0','\n'.join(reversed(LOGS[-25:])))
            self.root.after(1000,self.update_log)

        def update_hrv(self):
            if _recording and len(_rec_data)>1:
                self.hrv_canvas.delete("all"); w=self.hrv_canvas.winfo_width() or 450
                if w<50:w=450; h=100; pad=10
                vals=[v for _,v in _rec_data]
                if vals:
                    mn,mx=min(vals),max(vals); rng=max(mx-mn,1); n=len(vals)
                    for i in range(5):
                        y=h-pad-i*(h-2*pad)/4
                        self.hrv_canvas.create_line(pad,y,w-pad,y,fill='#1a1a24',width=0.5)
                        self.hrv_canvas.create_text(3,y,text=str(int(mn+i*rng/4)),fill='#3a3438',font=('Consolas',7),anchor='w')
                    pts=[]
                    for i,(t,v) in enumerate(_rec_data):
                        x=pad+i*(w-2*pad)/max(n-1,1); y=h-pad-(v-mn)/rng*(h-2*pad)
                        pts.append((x,y))
                    for i in range(len(pts)-1):
                        self.hrv_canvas.create_line(pts[i][0],pts[i][1],pts[i+1][0],pts[i+1][1],fill='#ff5d7c',width=1.5)
                    dur=int(time.time()-_rec_start)
                    self.hrv_canvas.create_text(w//2,h-4,text=f"{mn}-{mx} BPM  {len(vals)}条  {dur//60}分{dur%60}秒",fill='#998088',font=('Consolas',8))
                    self.hrv_label.config(text=f"📈 HRV 记录中: {len(vals)}条 ({dur//60}分{dur%60}秒)")
            self.root.after(2000,self.update_hrv)

        def toggle_hrv(self):
            global _recording,_rec_data,_rec_start
            if not _recording:
                s=state.snapshot()
                if not s['connected'] or s['hr']<=0:
                    messagebox.showwarning("提示","请先连接心率设备")
                    return
                _rec_data=[];_rec_start=time.time();_recording=True
                self.hrv_canvas.delete("all")
                self.hrv_canvas.create_text(200,40,text="🔴 记录中...",fill='#ff5d7c',font=('Consolas',12))
                log("⏺ HRV 开始记录")
            else:
                _recording=False; dur=time.time()-_rec_start
                log(f"⏹ HRV 结束 ({int(dur//60)}分{int(dur%60)}秒, {len(_rec_data)}条)")
                if dur>=30 and len(_rec_data)>=5:
                    self.draw_hrv_chart()
                else:
                    self.hrv_canvas.delete("all")
                    self.hrv_canvas.create_text(200,40,text="记录太短，需≥30秒",fill='#443a40',font=('Consolas',10))
                self.hrv_label.config(text=f"📈 HRV: {len(_rec_data)}条 已停止")

        def draw_hrv_chart(self):
            self.hrv_canvas.delete("all");w=self.hrv_canvas.winfo_width() or 450;h=100;pad=10
            vals=[v for _,v in _rec_data];mn,mx=min(vals),max(vals);rng=max(mx-mn,1);n=len(vals)
            if n<2:return
            for i in range(5):
                y=h-pad-i*(h-2*pad)/4
                self.hrv_canvas.create_line(pad,y,w-pad,y,fill='#1a1a24',width=0.5)
                self.hrv_canvas.create_text(3,y,text=str(int(mn+i*rng/4)),fill='#3a3438',font=('Consolas',7),anchor='w')
            pts=[]
            for i,(t,v) in enumerate(_rec_data):
                x=pad+i*(w-2*pad)/max(n-1,1); y=h-pad-(v-mn)/rng*(h-2*pad)
                pts.append((x,y))
            for i in range(len(pts)-1):
                self.hrv_canvas.create_line(pts[i][0],pts[i][1],pts[i+1][0],pts[i+1][1],fill='#ff5d7c',width=1.5)
            avg=sum(vals)/len(vals)
            detail=f"❤️ {mn}~{mx} BPM  avg={avg:.0f}  {len(vals)}条  {int((_rec_data[-1][0]-_rec_data[0][0])//60)}分"
            self.hrv_canvas.create_text(w//2,h-4,text=detail,fill='#998088',font=('Consolas',8))

        # ── 悬浮窗 ──
        def toggle_float(self):
            global _float_win,_float_label,_float_showing,_float_locked,_float_sidx
            if _float_showing:
                if _float_win:
                    try: _float_win.destroy()
                    except: pass
                _float_win=None;_float_label=None;_float_showing=False
                log("🔽 悬浮窗关闭")
            else:
                sty=_FLOAT_STYLES[_float_sidx]
                _float_win=tk.Toplevel(self.root)
                _float_win.title("心迹");_float_win.overrideredirect(True)
                _float_win.attributes("-topmost",True)
                _float_win.configure(bg='',highlightbackground=sty["border"],highlightthickness=1)
                sw=self.root.winfo_screenwidth();sh=self.root.winfo_screenheight()
                _float_win.geometry(f"130x70+{sw-155}+{sh-100}")
                _float_win.attributes("-alpha",0.85)
                c=tk.Frame(_float_win,bg=sty["bg"])
                c.pack(fill='both',expand=True)
                _float_label=tk.Label(c,text="--",font=('Consolas',32,'bold'),fg=sty["fg"],bg=sty["bg"])
                _float_label.pack(expand=True,pady=(5,0))
                tk.Label(c,text="BPM",font=('Segoe UI',7),fg=sty["fg"],bg=sty["bg"]).pack()
                def on_start(e):
                    if _float_locked: return
                    _float_win._drag_x=e.x_root;_float_win._drag_y=e.y_root
                def on_drag(e):
                    if _float_locked: return
                    x=_float_win.winfo_x()+e.x_root-_float_win._drag_x
                    y=_float_win.winfo_y()+e.y_root-_float_win._drag_y
                    _float_win.geometry(f"+{x}+{y}")
                    _float_win._drag_x=e.x_root;_float_win._drag_y=e.y_root
                _float_label.bind("<Button-1>",on_start)
                _float_label.bind("<B1-Motion>",on_drag)
                def on_right(e):
                    m=tk.Menu(_float_win,tearoff=0,bg='#1a1a24',fg='#e2c2cf')
                    m.add_command(label="🔒 锁定" if not _float_locked else "🔓 解锁",command=self.toggle_lock)
                    sm=tk.Menu(m,tearoff=0,bg='#1a1a24',fg='#e2c2cf')
                    for i,s in enumerate(_FLOAT_STYLES):
                        sm.add_command(label=s["name"],command=lambda idx=i: self.float_style(idx))
                    m.add_cascade(label="🎨 样式",menu=sm)
                    m.add_command(label="🎨 调字号",command=self.float_fontsize)
                    m.add_command(label="🚪 关闭",command=self.toggle_float)
                    m.tk_popup(e.x_root,e.y_root)
                _float_label.bind("<Button-3>",on_right)
                _float_showing=True
                log(f"🔼 悬浮窗 ({sty['name']})")

        def float_style(self, idx):
            global _float_sidx,_float_label
            _float_sidx=idx
            if _float_label:
                sty=_FLOAT_STYLES[idx]
                _float_label.config(fg=sty["fg"],bg=sty["bg"])
                _float_win.configure(highlightbackground=sty["border"])
                for child in _float_win.winfo_children():
                    try: child.configure(bg=sty["bg"])
                    except: pass
            log(f"🎨 样式: {sty['name']}")

        def toggle_lock(self):
            global _float_locked
            _float_locked=not _float_locked
            log(f"{'🔒' if _float_locked else '🔓'} 悬浮窗{'锁定' if _float_locked else '解锁'}")

        def float_fontsize(self):
            v=simpledialog.askinteger("字号","12~60:",initialvalue=32,minvalue=12,maxvalue=60)
            if v and _float_label: _float_label.config(font=('Consolas',v,'bold'))

        def mqtt_con(self):
            c=self.code_entry.get().strip()
            if not c or c=="粘贴连接码...": return
            self.mqtt_status.config(text="⏳ 连接中...")
            def r(): self.root.after(0,lambda: self.mqtt_status.config(text=mqtt_connect(c)))
            threading.Thread(target=r,daemon=True).start()
        def mqtt_dis(self):
            mqtt_disconnect(); self.mqtt_status.config(text="已断开")
        def mqtt_test(self):
            c=self.code_entry.get().strip()
            if not c or c=="粘贴连接码...": return
            self.mqtt_status.config(text="⏳ 测试中...")
            def r(): self.root.after(0,lambda: self.mqtt_status.config(text=test_mqtt(c)))
            threading.Thread(target=r,daemon=True).start()

        # ── 局域网 ──
        def lan_con(self):
            addr=self.lan_entry.get().strip()
            if not addr: return
            self.lan_status.config(text="⏳ 连接中...")
            threading.Thread(target=lambda: self.root.after(0,lambda: self.lan_status.config(text=start_lan_pull(addr) or "已连接")),daemon=True).start()
        def lan_dis(self):
            stop_lan_pull(); self.lan_status.config(text="已断开")
        def lan_scan(self):
            self.lan_status.config(text="⏳ 扫描中(约30秒)...")
            def r():
                devs=scan_lan()
                if devs:
                    ip,name=devs[0]
                    self.root.after(0,lambda: self.lan_entry.delete(0,'end'))
                    self.root.after(0,lambda: self.lan_entry.insert(0,f"{ip}:9090"))
                    self.root.after(0,lambda: self.lan_status.config(text=f"✅ 发现 {name}"))
                else:
                    self.root.after(0,lambda: self.lan_status.config(text="📭 未发现设备"))
            threading.Thread(target=r,daemon=True).start()

        def show_help(self):
            messagebox.showinfo("❓ 帮助",
                "1️⃣ 点击「测试」检测 Broker\n"
                "2️⃣ 用 mqtt.html 诊断防火墙\n"
                "3️⃣ 防火墙放行 1883 端口\n"
                "4️⃣ 更换 Broker 见文档")
        def run(self): self.root.mainloop()

# ═══════════ 启动 ═══════════
log("❤️ 心迹 v3.2 (悬浮窗+HRV+MQTT)")
_start_server()
log(f"浏览器: http://{ip()}:{UNIFIED_PORT}")

if _HAS_GUI:
    try: App().run()
    except Exception as e:
        log(f"GUI: {e}")
        try:
            while True: time.sleep(1)
        except KeyboardInterrupt:
            mqtt_disconnect(); log("👋 停止")
else:
    try:
        while True: time.sleep(1)
    except KeyboardInterrupt:
        mqtt_disconnect(); log("👋 停止")