package main

/*
#include <windows.h>

int g_connectReq = 0;
int g_disconnectReq = 0;
HWND g_hFloatWnd = NULL; // 悬浮窗句柄，供 Go 更新数字用

// 悬��窗窗口过程
LRESULT CALLBACK FloatWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    static int dragX = 0, dragY = 0;
    static int isDragging = 0;

    switch(msg) {
        case WM_LBUTTONDOWN:
            isDragging = 1;
            dragX = LOWORD(lParam);
            dragY = HIWORD(lParam);
            SetCapture(hwnd);
            return 0;
        case WM_MOUSEMOVE:
            if (isDragging) {
                RECT rect;
                GetWindowRect(hwnd, &rect);
                int x = rect.left + LOWORD(lParam) - dragX;
                int y = rect.top + HIWORD(lParam) - dragY;
                SetWindowPos(hwnd, NULL, x, y, 0, 0, SWP_NOSIZE | SWP_NOZORDER);
            }
            return 0;
        case WM_LBUTTONUP:
            isDragging = 0;
            ReleaseCapture();
            return 0;
        case WM_CLOSE:
            ShowWindow(hwnd, SW_HIDE);
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// 主窗口过程
LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    static HWND hFloatWnd = NULL;

    switch(msg) {
        case WM_DESTROY:
            if (hFloatWnd) DestroyWindow(hFloatWnd);
            PostQuitMessage(0);
            return 0;
        case WM_CTLCOLORSTATIC:
            SetTextColor((HDC)wParam, RGB(226, 194, 207));
            SetBkColor((HDC)wParam, RGB(12, 12, 16));
            return (LRESULT)GetStockObject(NULL_BRUSH);
        case WM_COMMAND:
            if (HIWORD(wParam) == BN_CLICKED) {
                switch(LOWORD(wParam)) {
                    case 17: // MQTT 连接
                        PostMessageW(hwnd, WM_APP + 1, 0, 0);
                        return 0;
                    case 18: // MQTT 断开
                        PostMessageW(hwnd, WM_APP + 2, 0, 0);
                        return 0;
                    case 20: // 显示悬浮窗
                        if (hFloatWnd) {
                            ShowWindow(hFloatWnd, SW_SHOW);
                            SetWindowPos(hFloatWnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
                        }
                        return 0;
                }
            }
case WM_APP + 1: // MQTT 连接按钮被点击
            g_connectReq = 1;
            return 0;
        case WM_APP + 2: // MQTT 断开
            g_disconnectReq = 1;
            return 0;
            return 0;
        case WM_APP + 10: // MQTT 连接结果回调（来自后台 goroutine）
            if (wParam == 1) {
                SetWindowTextA(GetDlgItem(hwnd, 17), "\xF0\x9F\x94\x97 连接");
                EnableWindow(GetDlgItem(hwnd, 17), 1);
                SetWindowTextA(GetDlgItem(hwnd, 19), "\xF0\x9F\x9F\xA2 MQTT 已连接");
            } else {
                SetWindowTextA(GetDlgItem(hwnd, 17), "\xF0\x9F\x94\x97 连接");
                EnableWindow(GetDlgItem(hwnd, 17), 1);
                SetWindowTextA(GetDlgItem(hwnd, 19), "\xE2\x9A\xAA MQTT 连接失败");
            }
            return 0;
        case WM_APP + 11: // 显示连接中（来自 tryConnectMQTT）
            SetWindowTextA(GetDlgItem(hwnd, 17), "\xE2\x8F\xB3 连接中...");
            EnableWindow(GetDlgItem(hwnd, 17), 0);
            return 0;
        case WM_APP + 12: // 重置按钮状态（来自 tryDisconnectMQTT）
            SetWindowTextA(GetDlgItem(hwnd, 17), "\xF0\x9F\x94\x97 连接");
            EnableWindow(GetDlgItem(hwnd, 17), 1);
            return 0;
        case WM_APP + 13: // 更新心率数字（来自后台 goroutine）
            {
                int hr = (int)wParam;
                wchar_t buf[16];
                if (hr > 30 && hr < 220) {
                    wsprintfW(buf, L"%d", hr);
                } else {
                    wcscpy(buf, L"--");
                }
                SetWindowTextW(GetDlgItem(hwnd, 2), buf);
                InvalidateRect(GetDlgItem(hwnd, 2), NULL, TRUE);
                UpdateWindow(GetDlgItem(hwnd, 2));
                // 同时更新悬浮窗
                if (g_hFloatWnd) {
                    SetWindowTextW(GetDlgItem(g_hFloatWnd, 101), buf);
                }
            }
            return 0;
        case WM_APP + 14: // 更新状态/设备名（来自后台 goroutine）
            SetWindowTextA(GetDlgItem(hwnd, 4), (const char*)wParam);
            SetWindowTextA(GetDlgItem(hwnd, 32), (const char*)lParam);
            free((void*)wParam);
            free((void*)lParam);
            return 0;
        case WM_APP + 15: // 更新悬浮窗数字
            {
                int hr = (int)wParam;
                wchar_t buf[16];
                if (hr > 30 && hr < 220) {
                    wsprintfW(buf, L"%d", hr);
                } else {
                    wcscpy(buf, L"--");
                }
                if (g_hFloatWnd) {
                    SetWindowTextW(GetDlgItem(g_hFloatWnd, 101), buf);
                }
            }
            return 0;
        case WM_APP + 3: // 创建悬浮窗
            {
                // 注册悬浮窗类
                const wchar_t FLOAT_CLASS[] = L"HeartFloatWindowClass";
                WNDCLASSW fwc = {0};
                fwc.lpfnWndProc = FloatWndProc;
                fwc.hInstance = GetModuleHandleW(NULL);
                fwc.hbrBackground = CreateSolidBrush(RGB(12, 12, 16));
                fwc.lpszClassName = FLOAT_CLASS;
                RegisterClassW(&fwc);

                // 创建置顶悬浮窗
                hFloatWnd = CreateWindowExW(
                    WS_EX_TOOLWINDOW | WS_EX_TOPMOST,
                    FLOAT_CLASS, L"❤️ 心率",
                    WS_POPUP | WS_BORDER,
                    GetSystemMetrics(SM_CXSCREEN) - 180, 40,
                    150, 90,
                    NULL, NULL, GetModuleHandleW(NULL), NULL
                );

                if (hFloatWnd) {
                    g_hFloatWnd = hFloatWnd; // 保存全局句柄
                    HFONT hFloatFont = CreateFontW(52, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
                        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, DEFAULT_QUALITY, DEFAULT_PITCH, L"Consolas");

                    // 心率数字
                    HWND hHR = CreateWindowW(L"STATIC", L"--",
                        WS_VISIBLE | WS_CHILD | SS_CENTER,
                        0, 5, 150, 55, hFloatWnd, (HMENU)101, GetModuleHandleW(NULL), NULL);
                    SendMessageW(hHR, WM_SETFONT, (WPARAM)hFloatFont, 0);
                    SetTextColor(GetDC(hHR), RGB(255, 93, 124));

                    // BPM
                    HFONT hSmall = CreateFontW(14, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, DEFAULT_QUALITY, DEFAULT_PITCH, L"Segoe UI");
                    HWND hBPM = CreateWindowW(L"STATIC", L"BPM",
                        WS_VISIBLE | WS_CHILD | SS_CENTER,
                        0, 55, 150, 18, hFloatWnd, (HMENU)102, GetModuleHandleW(NULL), NULL);
                    SendMessageW(hBPM, WM_SETFONT, (WPARAM)hSmall, 0);

                    ShowWindow(hFloatWnd, SW_SHOW);
                    SetWindowPos(hFloatWnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
                }
            }
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// 创建主窗口（含悬浮窗触发按钮）
HWND CreateAppWindow(HINSTANCE hInstance, int nCmdShow) {
    const wchar_t CLASS_NAME[] = L"HeartAppWindowClass";
    WNDCLASSW wc = {0};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.hIcon = LoadIconW(NULL, (LPCWSTR)IDI_APPLICATION);
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hbrBackground = CreateSolidBrush(RGB(12, 12, 16));
    wc.lpszClassName = CLASS_NAME;
    RegisterClassW(&wc);

    HWND hwnd = CreateWindowExW(
        0, CLASS_NAME, L"❤️ 心迹 - PC 心率监测",
        WS_OVERLAPPEDWINDOW & ~WS_MAXIMIZEBOX & ~WS_THICKFRAME,
        CW_USEDEFAULT, CW_USEDEFAULT, 440, 660,
        NULL, NULL, hInstance, NULL
    );

    if (hwnd) {
        HFONT hBigFont = CreateFontW(48, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, DEFAULT_QUALITY, DEFAULT_PITCH, L"Consolas");
        HFONT hFont = CreateFontW(18, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, DEFAULT_QUALITY, DEFAULT_PITCH, L"Segoe UI");
        HFONT hSmallFont = CreateFontW(13, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, DEFAULT_QUALITY, DEFAULT_PITCH, L"Consolas");

        // ─── 标题 ───
        CreateWindowW(L"STATIC", L"❤️ 心迹",
            WS_VISIBLE | WS_CHILD | SS_CENTER, 0, 8, 420, 28, hwnd, (HMENU)1, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 1), WM_SETFONT, (WPARAM)hFont, 0);

        // 心率
        CreateWindowW(L"STATIC", L"--",
            WS_VISIBLE | WS_CHILD | SS_CENTER, 0, 38, 420, 55, hwnd, (HMENU)2, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 2), WM_SETFONT, (WPARAM)hBigFont, 0);

        CreateWindowW(L"STATIC", L"BPM",
            WS_VISIBLE | WS_CHILD | SS_CENTER, 0, 90, 420, 16, hwnd, (HMENU)3, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 3), WM_SETFONT, (WPARAM)hSmallFont, 0);

	CreateWindowW(L"STATIC", L"\x25CF 等待连接...",
			WS_VISIBLE | WS_CHILD | SS_CENTER, 20, 112, 380, 22, hwnd, (HMENU)4, hInstance, NULL);
		SendMessageW(GetDlgItem(hwnd, 4), WM_SETFONT, (WPARAM)hFont, 0);

		// 设备名称
		CreateWindowW(L"STATIC", L"未连接",
			WS_VISIBLE | WS_CHILD | SS_CENTER, 20, 132, 380, 18, hwnd, (HMENU)32, hInstance, NULL);
		SendMessageW(GetDlgItem(hwnd, 32), WM_SETFONT, (WPARAM)hSmallFont, 0);

		// ─── 本地模式 ───
		CreateWindowW(L"STATIC", L"\xE2\x9A\xA1 本地模式",
			WS_VISIBLE | WS_CHILD | SS_CENTER, 0, 155, 420, 18, hwnd, (HMENU)13, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 13), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"STATIC", L"本地端口:",
            WS_VISIBLE | WS_CHILD | SS_LEFT, 20, 168, 150, 18, hwnd, (HMENU)5, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 5), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"EDIT", L"9091",
            WS_VISIBLE | WS_CHILD | WS_BORDER | ES_READONLY, 20, 186, 380, 22, hwnd, (HMENU)6, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 6), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"STATIC", L"\x1F4E5 手机推送:",
            WS_VISIBLE | WS_CHILD | SS_LEFT, 20, 215, 380, 18, hwnd, (HMENU)7, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 7), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"EDIT", L"http://---:9090/api/hr",
            WS_VISIBLE | WS_CHILD | WS_BORDER | ES_READONLY, 20, 233, 380, 22, hwnd, (HMENU)8, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 8), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"STATIC", L"\x1F4FA OBS 叠加:",
            WS_VISIBLE | WS_CHILD | SS_LEFT, 20, 262, 380, 18, hwnd, (HMENU)9, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 9), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"EDIT", L"http://---:9091?transparent=1",
            WS_VISIBLE | WS_CHILD | WS_BORDER | ES_READONLY, 20, 280, 380, 22, hwnd, (HMENU)10, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 10), WM_SETFONT, (WPARAM)hSmallFont, 0);

        // ─── MQTT 远程 ───
        CreateWindowW(L"STATIC", L"\xF0\x9F\x94\x97 MQTT 远程（粘贴连接码自动连接）",
            WS_VISIBLE | WS_CHILD | SS_CENTER, 0, 315, 420, 18, hwnd, (HMENU)14, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 14), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"EDIT", L"",
            WS_VISIBLE | WS_CHILD | WS_BORDER, 20, 340, 380, 26, hwnd, (HMENU)15, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 15), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"BUTTON", L"🔗 连接",
            WS_VISIBLE | WS_CHILD | BS_DEFPUSHBUTTON, 20, 372, 150, 26, hwnd, (HMENU)17, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 17), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"BUTTON", L"⏹ 断开",
            WS_VISIBLE | WS_CHILD | BS_PUSHBUTTON, 180, 372, 120, 26, hwnd, (HMENU)18, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 18), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"BUTTON", L"悬浮窗",
            WS_VISIBLE | WS_CHILD | BS_PUSHBUTTON, 310, 372, 90, 26, hwnd, (HMENU)20, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 20), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"STATIC", L"\xE2\x9A\xAA MQTT 未连接",
            WS_VISIBLE | WS_CHILD | SS_LEFT, 20, 405, 380, 18, hwnd, (HMENU)19, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 19), WM_SETFONT, (WPARAM)hSmallFont, 0);

        // ─── 提示 ───
        CreateWindowW(L"STATIC", L"\xE2\x93\x98 默认使用国内公共 Broker，也可自建或使用阿里云 IoT / EMQX",
            WS_VISIBLE | WS_CHILD | SS_LEFT, 20, 452, 380, 14, hwnd, (HMENU)21, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 21), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"STATIC", L"\xE2\x93\x98 校园网通常不会拦截 MQTT，如连不上请检查网络或更换 Broker",
            WS_VISIBLE | WS_CHILD | SS_LEFT, 20, 468, 380, 14, hwnd, (HMENU)22, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 22), WM_SETFONT, (WPARAM)hSmallFont, 0);

        // ─── 日志 ───
        CreateWindowW(L"STATIC", L"\x1F4CB 日志:",
            WS_VISIBLE | WS_CHILD | SS_LEFT, 20, 490, 380, 18, hwnd, (HMENU)11, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 11), WM_SETFONT, (WPARAM)hSmallFont, 0);

        CreateWindowW(L"EDIT", L"",
            WS_VISIBLE | WS_CHILD | WS_BORDER | ES_MULTILINE | ES_READONLY | WS_VSCROLL | ES_AUTOVSCROLL,
            20, 510, 380, 110, hwnd, (HMENU)12, hInstance, NULL);
        SendMessageW(GetDlgItem(hwnd, 12), WM_SETFONT, (WPARAM)hSmallFont, 0);

        // 创建悬浮窗
        PostMessageW(hwnd, WM_APP + 3, 0, 0);
    }
    return hwnd;
}
*/
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

// setTextW 正确设置 UTF-8 文本到 Windows 控件（解决中文乱码）
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

	// UI 频率限制
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

	if usbMode {
		setupUSB()
	}

	localPort = portCheck(9091)
	mainHTTPAddr = fmt.Sprintf("127.0.0.1:%d", localPort)

	go startLocalServer()
	connectSSE(fmt.Sprintf("http://%s/api/sse", phoneAddr))

	hInstance := C.GetModuleHandleW(nil)
	hwnd = C.CreateAppWindow(hInstance, C.int(1))
	if hwnd == nil {
		os.Exit(1)
	}
	C.ShowWindow(hwnd, C.int(1))
	C.UpdateWindow(hwnd)

	updateUIPorts()

	// 后台轮询按钮状态
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

func setupUSB() {
	cmd := exec.Command("adb", "forward", fmt.Sprintf("tcp:%d", localPort), "tcp:9090")
	out, err := cmd.CombinedOutput()
	_ = out
	if err != nil {
		os.Exit(1)
	}
	phoneAddr = fmt.Sprintf("127.0.0.1:%d", localPort)
}

func watchButtons() {
	for {
		time.Sleep(200 * time.Millisecond)
		if hwnd == nil {
			continue
		}

		// 轮询 C 代码设置的全局标志
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

	// 解析连接码
	broker, topic := parseCode(code)
	if broker == "" || topic == "" {
		addLog("连接码格式无效，请检查")
		addLog("格式: HEARTBEAT#V1#设备标识#IP:端口#Topic")
		return
	}

	addLog(fmt.Sprintf("正在连接 MQTT: %s topic: %s", broker, topic))
	// UI 操作通过 PostMessage 回到主线程
	C.PostMessageW(hwnd, 0x040B, 0, 0) // 显示"连接中..."

	// 后台连接，防止卡界面
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

// 后台连接完成后的回调
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

// 解析连接码，返回 (broker, topic)
// 格式: HEARTBEAT#V1#设备标识#host:port#topic
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

	// Topic 末尾加上设备标识
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
	// 重置按钮状态
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

// ─── 本地 SSE 连接 ───
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

// ─── UI 更新（全部通过 PostMessage 在主线程执行） ───
func updateUIHR() {
	if hwnd == nil {
		return
	}
	now := time.Now()
	if now.Sub(lastUIUpdate) < 200*time.Millisecond {
		return
	}
	lastUIUpdate = now
	// 加锁读取最新值
	mu.Lock()
	hr := currentHR
	dev := deviceName
	conn := connected
	mu.Unlock()
	// 投递到主线程
	postHRToUI(hr, dev, conn)
}

// forceUpdateHR 强制更新 UI，不经过频率限制
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
	// 直接更新主窗口数字（跨线程，但 Windows 对 SetWindowTextW 有保护）
	hrText := "--"
	if conn && hr > 30 && hr < 220 {
		hrText = fmt.Sprintf("%d", hr)
	}
	setTextW(C.GetDlgItem(hwnd, 2), hrText)
	// 强制刷新擦除残留
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
	// 悬浮窗
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
	// 限制日志更新频率，最多每 300ms 一次
	now := time.Now()
	if now.Sub(lastLogUpdate) < 300*time.Millisecond {
		return
	}
	lastLogUpdate = now
	
	t := time.Now().Format("15:04:05")
	logContent = fmt.Sprintf("[%s] %s\r\n", t, msg) + logContent
	if len(logContent) > 2000 {
		logContent = logContent[:2000]
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