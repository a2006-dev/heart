package main


import "C"
import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"
)


func setTextW(hwnd C.HWND, text string) {
	if hwnd == nil {
		return
	}
	t := C.CString(text)
	wlen := C.MultiByteToWideChar(C.CP_UTF8, 0, t, -1, nil, 0)
	if wlen > 0 {
		buf := make([]uint16, wlen)
		ptr := (*C.wchar_t)(unsafe.Pointer(&buf[0]))
		C.MultiByteToWideChar(C.CP_UTF8, 0, t, -1, ptr, wlen)
		C.SetWindowTextW(hwnd, ptr)
	} else {
		C.SetWindowTextA(hwnd, t)
	}
	C.free(unsafe.Pointer(t))
}

var (
	currentHR    = 0
	deviceName   = "未连接"
	connected    = false
	usbMode      = false
	phoneAddr    = "127.0.0.1:9090"
	localPort    = 9091
	hwnd         C.HWND
	logContent   = ""
	mainHTTPAddr = ""

	mu         sync.Mutex
	mqttActive = false
	mqttModeOn = false


	lastUIUpdate time.Time
	lastLogUpdate time.Time
)

func portCheck(base int) int {
	for i := 0; i < 20; i++ {
		p := base + i
		ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p))
		if err == nil {
			ln.Close()
			return p
		}
	}
	return base
}

func main() {
	for i := 1; i < len(os.Args); i++ {
		arg := os.Args[i]
		if arg == "-usb" {
			usbMode = true
		} else if !strings.HasPrefix(arg, "-") {
			phoneAddr = arg
		}
	}


	localPort = portCheck(9091)
	mainHTTPAddr = fmt.Sprintf("127.0.0.1:%d", localPort)

	if usbMode {
		if !setupUSB() {

			usbMode = false
		}
	}

	go startLocalServer()


	connectSSE(fmt.Sprintf("http://%s/", phoneAddr))

	hInstance := C.GetModuleHandleW(nil)
	hwnd = C.CreateAppWindow(hInstance, C.int(1))
	if hwnd == nil {
		os.Exit(1)
	}
	C.ShowWindow(hwnd, C.int(1))
	C.UpdateWindow(hwnd)

	updateUIPorts()


	go watchButtons()

	var msg C.MSG
	for {
		result := C.GetMessageW(&msg, nil, 0, 0)
		if result == 0 {
			break
		}
		C.TranslateMessage(&msg)
		C.DispatchMessageW(&msg)
	}
}


func setupUSB() bool {

	if _, err := exec.LookPath("adb"); err != nil {
		addLogForce("❌ 未找到 adb.exe —— 请将 adb.exe 放在本程序同目录，或加入系统 PATH")
		addLogForce("   下载：https://developer.android.com/studio/releases/platform-tools")
		return false
	}
	addLogForce("🔍 正在检测 adb 设备...")


	devCmd := exec.Command("adb", "devices")
	devOut, devErr := devCmd.CombinedOutput()
	if devErr != nil {
		addLogForce("❌ adb devices 执行失败：" + devErr.Error())
		return false
	}
	devText := string(devOut)
	addLogForce("adb devices 输出:\n" + strings.TrimSpace(devText))
	if !strings.Contains(devText, "\tdevice") {
		addLogForce("❌ 未检测到已授权设备。请检查：")
		addLogForce("   1. 手机已开启「USB 调试」")
		addLogForce("   2. 手机弹出的「允许USB调试」已点确定")
		addLogForce("   3. 数据线连接正常（非仅充电模式）")
		return false
	}


	phonePort := probePhoneBroadcastPort()
	if phonePort <= 0 {
		addLogForce("⚠️ 未探测到手机广播端口，默认使用 9090")
		addLogForce("   请确认手机端广播服务已启动")
		phonePort = 9090
	} else {
		addLogForce(fmt.Sprintf("✅ 探测到手机广播端口: %d", phonePort))
	}


	target := fmt.Sprintf("tcp:%d", phonePort)
	local := fmt.Sprintf("tcp:%d", localPort)
	cmd := exec.Command("adb", "forward", local, target)
	out, err := cmd.CombinedOutput()
	msg := strings.TrimSpace(string(out))
	if err != nil {
		addLogForce("❌ adb forward 失败：" + err.Error())
		if msg != "" {
			addLogForce("   adb 输出: " + msg)
		}
		addLogForce("   可能原因：")
		addLogForce("   - 端口 " + local + " 已被其他程序占用")
		addLogForce("   - adb 服务异常，尝试执行 adb kill-server 后重试")
		return false
	}
	if msg != "" {
		addLogForce("adb forward 输出: " + msg)
	}

	phoneAddr = fmt.Sprintf("127.0.0.1:%d", localPort)
	addLogForce(fmt.Sprintf("✅ USB 模式就绪：本机 %d → 手机 %d", localPort, phonePort))
	return true
}


func probePhoneBroadcastPort() int {

	for p := 9090; p <= 9099; p++ {
		if quickPortAlive(p) {
			return p
		}
	}
	return -1
}


func quickPortAlive(phonePort int) bool {
	testLocal := localPort
	cmd := exec.Command("adb", "forward",
		fmt.Sprintf("tcp:%d", testLocal), fmt.Sprintf("tcp:%d", phonePort))
	if out, err := cmd.CombinedOutput(); err != nil {
		_ = out
		return false
	}
	ok := false
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", testLocal), 800*time.Millisecond)
	if err == nil {
		ok = true
		conn.Close()
	}

	rm := exec.Command("adb", "forward", "--remove", fmt.Sprintf("tcp:%d", testLocal))
	_ = rm.Run()
	return ok
}

func watchButtons() {
	for {
		time.Sleep(200 * time.Millisecond)
		if hwnd == nil {
			continue
		}


		if C.g_connectReq != 0 {
			C.g_connectReq = 0
			tryConnectMQTT()
		}
		if C.g_disconnectReq != 0 {
			C.g_disconnectReq = 0
			tryDisconnectMQTT()
		}
	}
}

func getEditText(id int) string {
	buf := make([]C.char, 512)
	C.GetWindowTextA(C.GetDlgItem(hwnd, C.int(id)), &buf[0], C.int(len(buf)))
	return C.GoString(&buf[0])
}

func tryConnectMQTT() {
	if mqttActive {
		addLog("MQTT 已连接，请先断开再重连")
		return
	}

	code := getEditText(15)
	code = strings.TrimSpace(code)

	if code == "" {
		addLog("请在输入框中粘贴连接码")
		return
	}


	broker, topic := parseCode(code)
	if broker == "" || topic == "" {
		addLog("连接码格式无效，请检查")
		addLog("格式: HEARTBEAT#V1#设备标识#IP:端口#Topic")
		return
	}

	addLog(fmt.Sprintf("正在连接 MQTT: %s topic: %s", broker, topic))

	C.PostMessageW(hwnd, 0x040B, 0, 0)


	go func() {
		err := startMQTT(broker, topic)
		result := C.WPARAM(0)
		if err == nil && mqttClient != nil {
			result = C.WPARAM(1)
			mqttActive = true
			mqttModeOn = true
			addLog("MQTT 已连接，心率来源已切换至远程")
		} else {
			errMsg := "MQTT 连接失败"
			if err != nil {
				errMsg = fmt.Sprintf("MQTT 连接失败: %v", err)
			}
			addLog(errMsg)
		}
		C.PostMessageW(hwnd, 0x040A, result, 0)
	}()
}


func onMQTTConnectResult() {
	if mqttClient == nil {
		addLog("MQTT 连接失败，请检查网络和连接码")
		setTextW(C.GetDlgItem(hwnd, 17), "🔗 连接")
		C.EnableWindow(C.GetDlgItem(hwnd, 17), 1)
		updateMQTTStatus(false)
		return
	}

	mqttActive = true
	mqttModeOn = true
	addLog("MQTT 已连接，心率来源已切换至远程")
	setTextW(C.GetDlgItem(hwnd, 17), "🔗 连接")
	C.EnableWindow(C.GetDlgItem(hwnd, 17), 1)
	updateMQTTStatus(true)
}


func parseCode(code string) (string, string) {
	if !strings.HasPrefix(code, "HEARTBEAT#") {
		return "", ""
	}
	body := strings.TrimPrefix(code, "HEARTBEAT#")
	parts := strings.SplitN(body, "#", 4)
	if len(parts) < 4 {
		return "", ""
	}
	if parts[0] != "V1" {
		return "", ""
	}
	tag := parts[1]
	hostPort := parts[2]
	topicBase := parts[3]

	idx := strings.LastIndex(hostPort, ":")
	if idx <= 0 {
		return "", ""
	}
	host := hostPort[:idx]
	port := hostPort[idx+1:]


	fullTopic := topicBase
	if !strings.HasSuffix(fullTopic, "/"+tag) {
		fullTopic = fullTopic + "/" + tag
	}

	return host + ":" + port, fullTopic
}

func tryDisconnectMQTT() {
	stopMQTT()
	mqttActive = false
	mqttModeOn = false

	C.PostMessageW(hwnd, 0x040C, 0, 0)
	addLog("MQTT 已断开，已切换回本地模式")
	updateMQTTStatus(false)
}

func updateMQTTStatus(ok bool) {
	if hwnd == nil {
		return
	}
	text := "\xE2\x9A\xAA MQTT 未连接"
	if ok {
		text = "\xF0\x9F\x9F\xA2 MQTT 已连接"
	}
	setTextW(C.GetDlgItem(hwnd, 19), text)
}

func doUpdateMQTTStatus(ok bool) {
}


func connectSSE(url string) {
	go func() {
		for {
			err := connectSSEOnce(url)
			_ = err
			if !mqttModeOn {
				connected = false
				updateUIHR()
				updateFloatHR()
			}
			time.Sleep(3 * time.Second)
		}
	}()
}

func connectSSEOnce(url string) error {
	client := &http.Client{Timeout: 5 * time.Second}
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
				mu.Lock()
				if !mqttModeOn {
					currentHR = msg.HR
					deviceName = msg.Device
					connected = msg.Connected
				}
				mu.Unlock()
				updateUIHR()
				updateFloatHR()
			}
		}
	}
}


func updateUIHR() {
	if hwnd == nil {
		return
	}
	now := time.Now()
	if now.Sub(lastUIUpdate) < 200*time.Millisecond {
		return
	}
	lastUIUpdate = now

	mu.Lock()
	hr := currentHR
	dev := deviceName
	conn := connected
	mu.Unlock()

	postHRToUI(hr, dev, conn)
}


func forceUpdateHR() {
	if hwnd == nil {
		return
	}
	mu.Lock()
	hr := currentHR
	dev := deviceName
	conn := connected
	mu.Unlock()
	postHRToUI(hr, dev, conn)
}

func postHRToUI(hr int, dev string, conn bool) {

	hrText := "--"
	if conn && hr > 30 && hr < 220 {
		hrText = fmt.Sprintf("%d", hr)
	}
	setTextW(C.GetDlgItem(hwnd, 2), hrText)

	C.InvalidateRect(C.GetDlgItem(hwnd, 2), nil, 1)
	C.UpdateWindow(C.GetDlgItem(hwnd, 2))

	statusText := "\xE2\x97\x8B 等待连接..."
	deviceText := "未连接"
	if conn && hr > 30 {
		statusText = fmt.Sprintf("\xE2\x9C\x85 %d BPM", hr)
		deviceText = dev
	} else if conn {
		statusText = "\xE2\x9C\x85 已连接"
		deviceText = dev
	}
	setTextW(C.GetDlgItem(hwnd, 4), statusText)
	setTextW(C.GetDlgItem(hwnd, 32), deviceText)

	hwndFloat := C.g_hFloatWnd
	if hwndFloat != nil {
		setTextW(C.GetDlgItem(hwndFloat, 101), hrText)
	}
}

func updateFloatHR() {
	if hwnd == nil {
		return
	}
	hr := currentHR
	if connected && hr > 30 && hr < 220 {
		C.PostMessageW(hwnd, 0x040F, C.WPARAM(hr), 0)
	} else {
		C.PostMessageW(hwnd, 0x040F, C.WPARAM(0), 0)
	}
}

func updateUIPorts() {
	if hwnd == nil {
		return
	}
	setTextW(C.GetDlgItem(hwnd, 6), strconv.Itoa(localPort))
	setTextW(C.GetDlgItem(hwnd, 8), fmt.Sprintf("http://%s:9090/api/hr", getLocalIP()))
	setTextW(C.GetDlgItem(hwnd, 10), fmt.Sprintf("http://%s:%d?transparent=1", getLocalIP(), localPort))

	addLog(fmt.Sprintf("本地服务已就绪，端口: %d", localPort))
}

func doUpdateUIPorts() {
}

func getLocalIP() string {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "127.0.0.1"
	}
	defer conn.Close()
	addr := conn.LocalAddr().String()
	return strings.Split(addr, ":")[0]
}

func addLog(msg string) {

	now := time.Now()
	if now.Sub(lastLogUpdate) < 300*time.Millisecond {
		return
	}
	lastLogUpdate = now
	addLogForce(msg)
}


func addLogForce(msg string) {
	t := time.Now().Format("15:04:05")
	logContent = fmt.Sprintf("[%s] %s\r\n", t, msg) + logContent
	if len(logContent) > 4000 {
		logContent = logContent[:4000]
	}
	if hwnd != nil {
		setTextW(C.GetDlgItem(hwnd, 12), logContent)
	}
}

func startLocalServer() {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(appHTML))
	})
	mux.HandleFunc("/api/hr", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		hr, dev, conn := currentHR, deviceName, connected
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"hr": hr, "device": dev, "connected": conn,
		})
	})
	mux.HandleFunc("/api/sse", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		flusher, ok := w.(http.Flusher)
		if !ok {
			return
		}
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				mu.Lock()
				hr, dev, conn := currentHR, deviceName, connected
				mu.Unlock()
				data, _ := json.Marshal(map[string]interface{}{
					"hr": hr, "device": dev, "connected": conn,
				})
				fmt.Fprintf(w, "data: %s\n\n", data)
				flusher.Flush()
			case <-r.Context().Done():
				return
			}
		}
	})

	addLog("HTTP 服务器已启动")
	addLog("默认使用本地模式，下方可配置 MQTT 远程")
	addLog("提示: 校园网通常不拦截 MQTT，如连不上可换 broker")
	http.ListenAndServe(fmt.Sprintf(":%d", localPort), mux)
}

var appHTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><title>心迹 - 心率监测</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#0c0c10;color:#e2c2cf;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;min-height:100vh;padding:16px}
.card{background:#1a1a24;border-radius:16px;padding:20px;margin-bottom:12px;border:1px solid #2a2a34;width:100%;max-width:480px}
h1{text-align:center;color:#ff5d7c;font-size:22px;margin:8px 0}
.hr-num{font-size:72px;font-weight:800;font-family:monospace;color:#ff5d7c;text-align:center;line-height:1;text-shadow:0 0 20px rgba(255,60,100,0.3)}
.hr-unit{text-align:center;font-size:14px;color:#ffb0bd;margin-bottom:12px}
.status{display:flex;align-items:center;gap:8px;padding:8px 14px;background:#0c0c10;border-radius:10px;justify-content:center;font-size:14px}
.dot{width:10px;height:10px;border-radius:50%}
.dot.on{background:#4CAF50;box-shadow:0 0 8px #4CAF50}
.dot.off{background:#f44b6e}
.info{font-size:12px;color:#998088;margin-bottom:4px}
.val{font-family:monospace;font-size:13px;color:#ffb0bd;background:#0c0c10;padding:6px 10px;border-radius:8px;word-break:break-all;user-select:all}
.footer{text-align:center;font-size:11px;color:#4a3a40;padding:12px}
</style>
</head>
<body>
<h1>❤️ 心迹</h1>
<div class="card">
<div class="hr-num" id="hrV">--</div>
<div class="hr-unit">BPM</div>
<div class="status"><span class="dot off" id="dot"></span><span id="st">等待连接...</span></div>
</div>
<div class="card">
<div class="info">📥 手机推送地址</div>
<div class="val" id="pushUrl">加载中...</div>
<div style="margin-top:8px" class="info">📺 OBS 叠加</div>
<div class="val" id="obsUrl">加载中...</div>
</div>
<div class="card">
<div class="info">📡 运行信息</div>
<div class="info" id="logArea" style="margin-top:4px;line-height:1.6">启动中...</div>
</div>
<div class="footer">❤️ 心迹 · PC 心率监测</div>
<script>
var es=new EventSource('/api/sse'),last=0;
es.onmessage=function(e){
var d=JSON.parse(e.data);last=Date.now();
var el=document.getElementById('hrV'),dot=document.getElementById('dot'),st=document.getElementById('st');
if(d.connected&&d.hr>30&&d.hr<220){el.innerText=d.hr;el.style.color='#ff5d7c';dot.className='dot on';st.innerText='✅ '+d.device;}
else{el.innerText='--';el.style.color='#443a40';dot.className='dot off';st.innerText='⏳ 等待中...';}
};
</script>
</body>
</html>`
