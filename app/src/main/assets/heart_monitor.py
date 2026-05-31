# -*- coding: utf-8 -*-
"""
心迹 - 电脑心率悬浮窗 v2.2
跨平台心率接收 + 桌面悬浮窗 + 游戏模式（进程心率记录）

蓝牙 BLE 模块参考：https://github.com/milirstudio/xinxiu（心宿 · 米粒工作室）
"""

import json, threading, time, webbrowser, socket, os, sys, subprocess
from http.server import HTTPServer, BaseHTTPRequestHandler
from collections import deque

RECEIVE_PORT = 9091
QR_WEB_PORT = 9090

current_hr = 0; connected = False; device_name = "等待连接..."
connect_type = "等待连接"  # 连接方式：从手机推送获取 局域网/有线
hr_history = deque(maxlen=10)

# 游戏模式
game_mode_on = False          # 是否正在记录游戏
game_process_name = ""        # 监控的游戏进程名（如 "java.exe"）
game_hr_log = []              # [(timestamp, hr), ...]
game_start_time = 0

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2); s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]; s.close(); return ip
    except:
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None):
                ip = info[4][0]
                if ip.startswith("192.168.") or ip.startswith("10."): return ip
        except: pass
        return "127.0.0.1"

PC_IP = get_local_ip()
QR_HTML = f"""<!DOCTYPE html><html><head><meta charset="UTF-8">
<title>心迹 - 扫码连接</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box;}}
body{{font-family:-apple-system,'Microsoft YaHei',sans-serif;background:#0c0c10;color:#e2c2cf;
  display:flex;align-items:center;justify-content:center;min-height:100vh;}}
.box{{background:#1a1a24;border-radius:20px;padding:40px;text-align:center;
  border:1px solid rgba(255,93,124,0.2);max-width:420px;width:90%;}}
.title{{font-size:22px;color:#ff5d7c;margin-bottom:20px;font-weight:bold;}}
.qr-icon{{font-size:64px;margin:20px auto;}}
.ip{{font-size:20px;font-weight:bold;color:#ff5d7c;background:#0c0c10;padding:10px;
  border-radius:8px;margin:12px 0;font-family:monospace;word-break:break-all;}}
.info{{font-size:14px;color:#675c62;line-height:1.8;}}
</style></head><body>
<div class="box"><div class="title">📡 心迹 · 扫码连接电脑</div>
<div class="qr-icon">📱→🖥️</div>
<div class="ip">{PC_IP}:{RECEIVE_PORT}</div>
<div class="info">用心迹APP扫码连接<br>或在APP手动输入上方地址</div>
</div></body></html>"""

class QRHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type','text/html;charset=utf-8')
        self.send_header('Cache-Control','no-cache')
        self.end_headers()
        self.wfile.write(QR_HTML.encode('utf-8'))
    def log_message(self,*a): pass

class PushHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        l = int(self.headers.get('Content-Length',0))
        if l>0:
            # 记录来源IP，用于识别连接方式
            handle_push.__dict__['last_ip'] = self.client_address[0]
            handle_push(self.rfile.read(l).decode('utf-8'))
        self.send_response(200); self.end_headers()
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type','application/json;charset=utf-8')
        self.end_headers()
        self.wfile.write(json.dumps({'status':'running','hr':current_hr,
            'device':device_name,'connect_type':connect_type}).encode())
    def log_message(self,*a): pass

def handle_push(data):
    global current_hr,connected,device_name,last_hr_time,hr_history,connect_type
    try:
        d = json.loads(data); hr = d.get("hr",0)
        if isinstance(hr,(int,float)) and 0<hr<250:
            current_hr = int(hr); connected=True; last_hr_time=time.time()
            hr_history.append(current_hr)
            # 游戏模式记录
            if game_mode_on:
                game_hr_log.append((time.time(), current_hr))
        if d.get("device"): device_name = d["device"]
        # 直接用手机推过来的连接方式
        if d.get("connect_type"):
            connect_type = d["connect_type"]
    except: pass

def start_servers():
    try:
        HTTPServer(('0.0.0.0',QR_WEB_PORT),QRHandler).serve_forever()
    except: print(f"⚠️ 二维码页面端口 {QR_WEB_PORT} 被占用")
def start_push():
    try:
        HTTPServer(('0.0.0.0',RECEIVE_PORT),PushHandler).serve_forever()
    except: print(f"⚠️ 推送端口 {RECEIVE_PORT} 被占用")

threading.Thread(target=start_servers,daemon=True).start()
threading.Thread(target=start_push,daemon=True).start()

print(f"\n{'='*50}")
print(f"  心迹 - 电脑心率悬浮窗 v2.2")
print(f"{'='*50}")
print(f"  扫码页面: http://{PC_IP}:{QR_WEB_PORT}")
print(f"  推送地址: {PC_IP}:{RECEIVE_PORT}")
print(f"  连接方式: 等待手机连接...")
print(f"{'='*50}\n")

try: webbrowser.open(f"http://127.0.0.1:{QR_WEB_PORT}")
except: pass

# ===== Tkinter 悬浮窗 + 游戏模式 =====
try:
    import tkinter as tk
    from tkinter import simpledialog, messagebox, colorchooser
except ImportError:
    print("需要 tkinter 才能显示悬浮窗。安装: pip install tk")
    print(f"浏览器访问 http://127.0.0.1:{QR_WEB_PORT} 查看二维码")
    while True: time.sleep(60)

root = tk.Tk()
root.title("心迹 - 心率悬浮窗")
root.overrideredirect(True)
root.wm_attributes("-topmost", True)

CONFIG = {'bg':'#0c0c10','op':0.75,'fg':'#ff5d7c','fs':36,'w':150,'h':80,'border':True,'heartbeat':True}

frame = tk.Frame(root, bg=CONFIG['bg'])
hr_label = tk.Label(frame, text="--", font=("Consolas",CONFIG['fs'],"bold"),
    fg=CONFIG['fg'], bg=CONFIG['bg'])
unit_label = tk.Label(frame, text="❤️ BPM", font=("Segoe UI",11),
    fg='#ffb0bd', bg=CONFIG['bg'])
dev_label = tk.Label(frame, text="等待连接...", font=("Microsoft YaHei",9),
    fg='#998088', bg=CONFIG['bg'])

def rebuild_ui():
    bg = CONFIG['bg']; op = CONFIG['op']
    if op < 1.0:
        try:
            r=int(int(bg[1:3],16)*op); g=int(int(bg[3:5],16)*op); b=int(int(bg[5:7],16)*op)
            bg=f'#{r:02x}{g:02x}{b:02x}'
        except: pass
    root.configure(bg=bg)
    w=CONFIG['w']; h=CONFIG['h']
    sw = max(root.winfo_screenwidth(),800)
    root.geometry(f"{w}x{h}+{sw-w-20}+30")
    for c in (frame,hr_label,unit_label,dev_label):
        c.configure(bg=bg)
    frame.configure(highlightbackground=CONFIG['fg'],
        highlightthickness=1 if CONFIG['border'] else 0)
    hr_label.configure(font=("Consolas",CONFIG['fs'],"bold"),fg=CONFIG['fg'])
    hr_label.pack(pady=(int(h*0.1),0))
    unit_label.pack(); dev_label.pack()

rebuild_ui(); _drag={'x':0,'y':0}
def sd(e): _drag['x'],_drag['y']=e.x,e.y
def dd(e): root.geometry(f"+{root.winfo_x()+e.x-_drag['x']}+{root.winfo_y()+e.y-_drag['y']}")
for w in (frame,hr_label,unit_label,dev_label):
    w.bind("<Button-1>",sd); w.bind("<B1-Motion>",dd)

def heartbeat_loop():
    if CONFIG['heartbeat'] and connected and current_hr>30:
        bpm=min(current_hr,200); iv=max(0.3,60/bpm)
        s=min(max(1+(bpm-60)/200,1),1.5)
        hr_label.config(font=("Consolas",int(CONFIG['fs']*s),"bold"))
        if bpm<70: hr_label.config(fg='#4CAF50')
        elif bpm<100: hr_label.config(fg=CONFIG['fg'])
        elif bpm<140: hr_label.config(fg='#ff3d00')
        else: hr_label.config(fg='#d50000')
        root.after(max(100,int(iv*200)), lambda: (
            hr_label.config(font=("Consolas",CONFIG['fs'],"bold"),fg=CONFIG['fg']),
            root.after(max(100,int(iv*800)), heartbeat_loop)))
    else: root.after(500, heartbeat_loop)

def upd():
    hr_label.config(text=str(current_hr) if connected and current_hr>30 else "--")
    status = device_name
    if connected and connect_type:
        status += f" ｜{connect_type}"
    dev_label.config(text=status)
    root.after(300, upd)

# ====== 游戏模式功能 ======
def start_game_mode():
    """输入要监控的进程名，开始记录心率变化"""
    global game_mode_on,game_process_name,game_hr_log,game_start_time
    pname = simpledialog.askstring("游戏模式",
        "输入要监控的进程名（如 java.exe, chrome.exe, python.exe）\n\n"
        "脚本会检测该进程是否在运行，运行中则持续记录心率",
        title="心迹 - 游戏模式")
    if not pname: return
    game_process_name = pname.strip()
    game_hr_log = []; game_start_time = time.time(); game_mode_on = True
    game_log_msg(f"📝 开始监控 [ {game_process_name} ] 的心率")
    def check_process():
        while game_mode_on:
            alive = False
            try:
                if sys.platform == 'win32':
                    r = subprocess.run(f'tasklist /FI "IMAGENAME eq {game_process_name}"',
                        shell=True, capture_output=True, text=True, timeout=3)
                    alive = game_process_name.lower() in r.stdout.lower()
                else:
                    r = subprocess.run(['pgrep','-x',game_process_name],
                        capture_output=True, timeout=3)
                    alive = r.returncode == 0
            except: pass
            if not alive:
                game_mode_on = False
                save_game_log()
                game_log_msg(f"⏹️ 进程 [ {game_process_name} ] 已退出，记录已保存")
                break
            time.sleep(2)
    threading.Thread(target=check_process, daemon=True).start()

def stop_game_mode():
    global game_mode_on
    if game_mode_on:
        game_mode_on = False
        save_game_log()
        game_log_msg("⏹️ 游戏记录已手动停止")

def save_game_log():
    if len(game_hr_log) < 5: return
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    filename = f"heart_game_{game_process_name}_{timestamp}.csv"
    try:
        with open(filename, 'w', encoding='utf-8') as f:
            f.write("time,heart_rate\n")
            for t, h in game_hr_log:
                f.write(f"{time.strftime('%H:%M:%S',time.localtime(t))},{h}\n")
        game_log_msg(f"💾 已保存 {len(game_hr_log)} 条记录 → {filename}")
    except Exception as e:
        game_log_msg(f"❌ 保存失败: {e}")

LOG_LINES = []
def game_log_msg(msg):
    LOG_LINES.append(msg)
    if len(LOG_LINES) > 50: LOG_LINES.pop(0)

# 右键菜单
def show_settings(event):
    m = tk.Menu(root, tearoff=0, bg='#1a1a24', fg='#e2c2cf',
        activebackground='#ff5d7c', activeforeground='#fff')
    m.add_command(label="🎨 背景色...", command=lambda: change_color('bg','选择背景色'))
    m.add_command(label="✏️ 文字色...", command=lambda: change_color('fg','选择文字颜色'))
    m.add_command(label="📏 字体大小...", command=lambda: change_font())
    m.add_command(label="🔲 透明度...", command=lambda: change_op())
    m.add_separator()
    m.add_command(label=f"{'✅' if CONFIG['heartbeat'] else '❌'} 心跳动画",
        command=lambda: toggle('heartbeat'))
    m.add_command(label=f"{'✅' if CONFIG['border'] else '❌'} 边框",
        command=lambda: toggle('border'))
    m.add_separator()
    m.add_command(label="🎮 启动游戏模式...", command=start_game_mode)
    m.add_command(label="⏹️ 停止游戏模式", command=stop_game_mode)
    m.add_command(label="📊 查看游戏记录", command=show_game_log)
    m.add_separator()
    m.add_command(label="🔄 重置设置", command=reset_all)
    m.add_separator()
    m.add_command(label="🚪 退出", command=root.destroy)
    try: m.tk_popup(event.x_root, event.y_root)
    finally: m.grab_release()

def change_color(k,t):
    c = colorchooser.askcolor(title=t, color=CONFIG[k])
    if c and c[1]: CONFIG[k]=c[1]; rebuild_ui()
def change_font():
    s = simpledialog.askinteger("字号","(12-80):",initialvalue=CONFIG['fs'],minvalue=12,maxvalue=80)
    if s: CONFIG['fs']=s; rebuild_ui()
def change_op():
    s = simpledialog.askfloat("透明度","(0.1-1.0):",initialvalue=CONFIG['op'],minvalue=0.1,maxvalue=1.0)
    if s: CONFIG['op']=s; rebuild_ui()
def toggle(k): CONFIG[k]=not CONFIG[k]; rebuild_ui()
def reset_all():
    CONFIG.update({'bg':'#0c0c10','op':0.75,'fg':'#ff5d7c','fs':36,'w':150,'h':80,'border':True,'heartbeat':True})
    rebuild_ui(); messagebox.showinfo("心迹","已重置")

def show_game_log():
    if not LOG_LINES:
        messagebox.showinfo("游戏日志","暂无记录")
        return
    msg = "\n".join(LOG_LINES[-20:])
    messagebox.showinfo("游戏记录日志", msg)

root.bind("<Button-3>", show_settings)
root.bind("<Escape>", lambda e: root.destroy())
root.after(1000, heartbeat_loop)
upd()
print("✅ 悬浮窗已启动！右键可设置或启动游戏模式。")
root.mainloop()
