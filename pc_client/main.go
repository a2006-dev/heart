package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// ============================================================
// 心迹 PC 客户端 - 独立 exe，不需要 Python/任何运行时
// 从手机广播服务器接收心率数据，显示悬浮窗
//
// 编译：go build -ldflags="-H windowsgui -s -w" -o heart_client.exe
// 体积：~3MB (压缩后 ~1.5MB)
// ============================================================

var currentHR = 0
var deviceName = "未连接"
var connected = false

// USB 模式相关
var usbMode = false
var adbForwardPort = 9091

func main() {
	// 默认配置
	addr := "127.0.0.1:9090"

	// 命令行参数：heart_client.exe 192.168.1.5:9090
	//             heart_client.exe -usb
	for i := 1; i < len(os.Args); i++ {
		arg := os.Args[i]
		if arg == "-usb" {
			usbMode = true
		} else if !strings.HasPrefix(arg, "-") {
			addr = arg
		}
	}

	// USB 模式：自动 adb forward + 连 localhost
	if usbMode {
		fmt.Println("🔌 USB 模式：通过 ADB 连接手机...")
		cmd := exec.Command("adb", "forward", fmt.Sprintf("tcp:%d", adbForwardPort), "tcp:9090")
		out, err := cmd.CombinedOutput()
		if err != nil {
			fmt.Printf("⚠️ ADB forward 失败: %v\n%s\n", err, string(out))
			fmt.Println("请确保：")
			fmt.Println("  1. 手机已通过 USB 连接电脑")
			fmt.Println("  2. 已开启 USB 调试")
			fmt.Println("  3. ADB 已安装并可用")
			os.Exit(1)
		}
		fmt.Printf("✅ ADB forward 成功: tcp:%d → tcp:9090\n", adbForwardPort)
		addr = fmt.Sprintf("127.0.0.1:%d", adbForwardPort)
	}

	// 解析 IP 和端口
	ip := addr
	port := 9090
	if strings.Contains(addr, ":") {
		parts := strings.Split(addr, ":")
		ip = parts[0]
		if len(parts) > 1 {
			p, err := strconv.Atoi(parts[1])
			if err == nil {
				port = p
			}
		}
	}

	fmt.Printf("心迹 PC 客户端 v1.0\n")
	fmt.Printf("连接服务器: %s:%d\n", ip, port)
	fmt.Println("按 Ctrl+C 退出")
	fmt.Println()

	// 启动 HTTP 服务器（本地，供浏览器访问）
	go startLocalServer(port)

	// 连接手机 SSE
	connectSSE(fmt.Sprintf("http://%s:%d/api/sse", ip, port))

	// 保活
	select {}
}

// ============================================================
// SSE 连接 — 从手机接收心率数据
// ============================================================
func connectSSE(url string) {
	go func() {
		for {
			err := connectSSEOnce(url)
			fmt.Printf("连接断开，3秒后重连... (%v)\n", err)
			connected = false
			time.Sleep(3 * time.Second)
		}
	}()
}

func connectSSEOnce(url string) error {
	client := &http.Client{Timeout: 0}
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "text/event-stream")
	req.Header.Set("Cache-Control", "no-cache")

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	fmt.Println("✅ 已连接到手机心率服务器")

	reader := bufio.NewReader(resp.Body)
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return err
		}
		line = strings.TrimSpace(line)

		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimPrefix(line, "data: ")
			var msg struct {
				HR        int    `json:"hr"`
				Device    string `json:"device"`
				Connected bool   `json:"connected"`
			}
			if err := json.Unmarshal([]byte(data), &msg); err == nil {
				currentHR = msg.HR
				deviceName = msg.Device
				connected = msg.Connected
				onHeartRate(msg.HR, msg.Device, msg.Connected)
			}
		}
	}
}

// ============================================================
// 本地 HTTP 服务 — 提供浏览器可访问的页面
// ============================================================
func startLocalServer(port int) {
	mux := http.NewServeMux()

	// 主页
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(indexHTML))
	})

	// API
	mux.HandleFunc("/api/hr", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"hr":        currentHR,
			"device":    deviceName,
			"connected": connected,
		})
	})

	// SSE（转发手机数据）
	mux.HandleFunc("/api/sse", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")
		w.Header().Set("Access-Control-Allow-Origin", "*")

		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
			return
		}

		// 发送初始数据
		sendSSE(w, flusher)
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				sendSSE(w, flusher)
			case <-r.Context().Done():
				return
			}
		}
	})

	addr := fmt.Sprintf(":%d", 9091) // 本地服务用 9091 端口
	fmt.Printf("本地页面: http://localhost%s\n", addr)
	fmt.Printf("二维码扫码后请访问本地页面\n")
	if err := http.ListenAndServe(addr, mux); err != nil {
		fmt.Printf("本地服务器启动失败: %v\n", err)
	}
}

func sendSSE(w io.Writer, flusher http.Flusher) {
	data, _ := json.Marshal(map[string]interface{}{
		"hr":        currentHR,
		"device":    deviceName,
		"connected": connected,
	})
	fmt.Fprintf(w, "data: %s\n\n", data)
	flusher.Flush()
}

// ============================================================
// 控制台输出心率
// ============================================================
func onHeartRate(hr int, device string, conn bool) {
	status := "❌ 离线"
	if conn {
		status = "✅ 在线"
	}
	fmt.Printf("\r❤️ %3d BPM  |  %s  |  %s    ", hr, device, status)
}

// ============================================================
// 内嵌 HTML 页面（编译进 exe，无需外部文件）
// ============================================================
var indexHTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>心迹 - PC 心率悬浮窗</title>
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body {
    width:100vw; height:100vh; overflow:hidden;
    font-family: 'Segoe UI', -apple-system, sans-serif;
    background: transparent !important;
    display:flex; align-items:center; justify-content:center;
  }
  .float-window {
    position:fixed; top:20px; right:20px; z-index:9999;
    cursor:grab; user-select:none;
    display:flex; flex-direction:column; align-items:center;
    gap:2px; padding:12px 20px;
    background: rgba(12,12,16,0.75);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 18px;
    border: 1px solid rgba(255,93,124,0.2);
    box-shadow: 0 8px 32px rgba(0,0,0,0.5);
    min-width: 140px;
  }
  .float-window:active { cursor:grabbing; }
  .float-window.transparent {
    background: transparent !important;
    backdrop-filter: none !important;
    border: none !important;
    box-shadow: none !important;
    padding: 4px !important;
    min-width: auto !important;
  }
  .float-window.offline { opacity:0.45; filter:grayscale(0.8); }
  .float-window.offline::after {
    content:"⚠ 离线"; position:absolute; top:-4px; right:-4px;
    background:#ff3333; color:#fff; font-size:9px; font-weight:bold;
    padding:1px 8px; border-radius:10px; pointer-events:none;
  }
  .hr-row { display:flex; align-items:baseline; gap:3px; }
  .hr-number {
    font-size: 3rem; font-weight:800;
    font-family: 'Consolas', monospace;
    color: #ff5d7c;
    text-shadow: 0 0 20px rgba(255,60,100,0.4);
    line-height:1; letter-spacing:-1px;
    font-variant-numeric: tabular-nums;
    min-width: 60px; text-align:right;
  }
  .float-window.transparent .hr-number { font-size:2.2rem; text-shadow:0 0 10px rgba(255,60,100,0.6); }
  .hr-unit { font-size:0.85rem; color:#ffb0bd; font-weight:600; }
  .status-row { display:flex; align-items:center; gap:6px; margin-top:2px; }
  .pulse-dot {
    width:7px; height:7px; border-radius:50%;
    background:#f44b6e; box-shadow:0 0 8px #ff3e64;
    transition: transform 0.1s;
  }
  .pulse-dot.beat { transform:scale(2); }
  .pulse-dot.offline { background:#444; box-shadow:none; }
  .device-name { font-size:0.65rem; color:#998088; }
  .float-window.offline .hr-number { color:#665c62; text-shadow:none; }
  @keyframes blink { 0%,100%{opacity:1} 50%{opacity:0.25} }
  .float-window.stale .hr-number { animation:blink 1s infinite; color:#ffaa00; text-shadow:0 0 14px rgba(255,170,0,0.5); }
</style>
</head>
<body>
<div class="float-window" id="fw">
  <div class="hr-row">
    <span class="hr-number" id="hrVal">--</span>
    <span class="hr-unit">BPM</span>
  </div>
  <div class="status-row">
    <div class="pulse-dot" id="pulse"></div>
    <span class="device-name" id="devName">等待连接…</span>
  </div>
</div>
<script>
  var fw=document.getElementById('fw'), dx,dy,ox,oy, dragging=false;
  fw.addEventListener('mousedown',function(e){dragging=true;dx=e.clientX;dy=e.clientY;ox=fw.offsetLeft;oy=fw.offsetTop;e.preventDefault();});
  document.addEventListener('mousemove',function(e){if(!dragging)return;fw.style.left=(ox+e.clientX-dx)+'px';fw.style.top=(oy+e.clientY-dy)+'px';fw.style.right='auto';});
  document.addEventListener('mouseup',function(){dragging=false;});
  if(location.search.includes('transparent'))fw.classList.add('transparent');
  var es=new EventSource('/api/sse'), last=0;
  es.onmessage=function(e){
    var d=JSON.parse(e.data);last=Date.now();fw.classList.remove('stale');
    var el=document.getElementById('hrVal'),p=document.getElementById('pulse'),n=document.getElementById('devName');
    if(d.connected&&d.hr>30&&d.hr<220){
      el.innerText=d.hr;fw.classList.remove('offline');p.classList.remove('offline');
      p.classList.remove('beat');void p.offsetWidth;p.classList.add('beat');setTimeout(function(){p.classList.remove('beat');},200);
    }else{el.innerText='--';fw.classList.add('offline');p.classList.add('offline');}
    n.innerText=d.device||'等待连接…';
  };
  setInterval(function(){if(Date.now()-last>5000&&last>0)fw.classList.add('stale');},1000);
</script>
</body>
</html>`
