package main

/*
#include <windows.h>

int g_connectReq = 0;
int g_disconnectReq = 0;
HWND g_hFloatWnd = NULL; // 悬浮窗句柄，供 Go 更新数字用

// 悬浮窗窗口过程
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
            break;
        case WM_APP + 1: // MQTT 连接按钮被点击
            g_connectReq = 1;
            return 0;
        case WM_APP + 2: // MQTT 断开
            g_disconnectReq = 1;
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
                if (hrValid(hr)) {
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
                if (hrValid(hr)) {
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

	logMu    sync.Mutex
	logLines []string
	logDirty bool
	logTimer *time.Timer
)

func isMqttActive() bool {
	mu.Lock()
	defer mu.Unlock()
	return mqttActive
}

func setMqttActive(v bool) {
	mu.Lock()
	mqttActive = v
	mu.Unlock()
}

func isMqttModeOn() bool {
	mu.Lock()
	defer mu.Unlock()
	return mqttModeOn
}

func setMqttModeOn(v bool) {
	mu.Lock()
	mqttModeOn = v
	mu.Unlock()
}

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
	if isMqttActive() {
		addLog("MQTT 已连接，请先断开再重连")
		return
	}

	code := getEditText(15)
	code = strings.TrimSpace(code)

	if code == "" {
		addLog("请在输入框中粘贴连接码")
		return
	}


	broker, topic, useTLS := parseCode(code)
	if broker == "" || topic == "" {
		addLog("连接码格式无效，请检查")
		addLog("格式: HEARTBEAT#V1#设备标识#IP:端口#Topic")
		addLog("加密格式: HEARTBEAT#V2#设备标识#IP:端口#Topic")
		return
	}

	addLog(fmt.Sprintf("正在连接 MQTT: %s topic: %s", broker, topic))

	C.PostMessageW(hwnd, 0x040B, 0, 0)


	go func() {
		err := startMQTT(broker, topic, useTLS)
		result := C.WPARAM(0)
		if err == nil && getMqttClient() != nil {
			result = C.WPARAM(1)
			setMqttActive(true)
			setMqttModeOn(true)
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
	if getMqttClient() == nil {
		addLog("MQTT 连接失败，请检查网络和连接码")
		setTextW(C.GetDlgItem(hwnd, 17), "🔗 连接")
		C.EnableWindow(C.GetDlgItem(hwnd, 17), 1)
		updateMQTTStatus(false)
		return
	}

	setMqttActive(true)
	setMqttModeOn(true)
	addLog("MQTT 已连接，心率来源已切换至远程")
	setTextW(C.GetDlgItem(hwnd, 17), "🔗 连接")
	C.EnableWindow(C.GetDlgItem(hwnd, 17), 1)
	updateMQTTStatus(true)
}


func parseCode(code string) (string, string, bool) {
	if !strings.HasPrefix(code, "HEARTBEAT#") {
		return "", "", false
	}
	body := strings.TrimPrefix(code, "HEARTBEAT#")
	parts := strings.SplitN(body, "#", 4)
	if len(parts) < 4 {
		return "", "", false
	}
	useTLS := parts[0] == "V2"
	if !useTLS && parts[0] != "V1" {
		return "", "", false
	}
	tag := strings.TrimSpace(parts[1])
	hostPort := strings.TrimSpace(parts[2])
	topicBase := strings.TrimSpace(parts[3])
	if tag == "" || hostPort == "" || topicBase == "" {
		return "", "", false
	}
	var host, port string
	bracketHost := ""
	if strings.HasPrefix(hostPort, "[") {
		closeIdx := strings.Index(hostPort, "]")
		if closeIdx < 0 || closeIdx+1 >= len(hostPort) || hostPort[closeIdx+1] != ':' {
			return "", "", false
		}
		host = hostPort[1:closeIdx]
		bracketHost = hostPort[:closeIdx+1]
		port = hostPort[closeIdx+2:]
	} else {
		idx := strings.LastIndex(hostPort, ":")
		if idx <= 0 {
			return "", "", false
		}
		host = hostPort[:idx]
		port = hostPort[idx+1:]
	}
	if host == "" || port == "" {
		return "", "", false
	}
	for _, c := range port {
		if c < '0' || c > '9' {
			return "", "", false
		}
	}
	portNum, err := strconv.Atoi(port)
	if err != nil || portNum < 1 || portNum > 65535 {
		return "", "", false
	}
	fullTopic := topicBase
	if !strings.HasSuffix(fullTopic, "/"+tag) {
		fullTopic = fullTopic + "/" + tag
	}
	if bracketHost != "" {
		return bracketHost + ":" + port, fullTopic, useTLS
	}
	return host + ":" + port, fullTopic, useTLS
}

func tryDisconnectMQTT() {
	stopMQTT()
	setMqttActive(false)
	setMqttModeOn(false)

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
			if !isMqttModeOn() {
				connected = false
				updateUIHR()
				updateFloatHR()
			}
			time.Sleep(3 * time.Second)
		}
	}()
}

func connectSSEOnce(url string) error {
	// SSE 是长连接，不能设置整体超时，否则固定时间后会被强制断开。
	// 只限制连接建立和响应头等待时间，数据流本身不设上限。
	transport := &http.Transport{
		DialContext: (&net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ResponseHeaderTimeout: 10 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		IdleConnTimeout:       0,
	}
	client := &http.Client{
		Transport: transport,
		// 不设 Timeout，SSE 流需要长期保持
	}
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
				if !isMqttModeOn() {
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

const (
	hrMin      = 20
	hrMax      = 250
	hrAbnormal = "数值异常"
)

func hrValid(hr int) bool {
	return hr >= hrMin && hr <= hrMax
}

func postHRToUI(hr int, dev string, conn bool) {

	hrText := "--"
	if conn && hrValid(hr) {
		hrText = fmt.Sprintf("%d", hr)
	} else if conn && hr > 0 {
		hrText = hrAbnormal
	}
	setTextW(C.GetDlgItem(hwnd, 2), hrText)

	C.InvalidateRect(C.GetDlgItem(hwnd, 2), nil, 1)
	C.UpdateWindow(C.GetDlgItem(hwnd, 2))

	statusText := "\xE2\x97\x8B 等待连接..."
	deviceText := "未连接"
	if conn && hrValid(hr) {
		statusText = fmt.Sprintf("\xE2\x9C\x85 %d BPM", hr)
		deviceText = dev
	} else if conn && hr > 0 {
		statusText = "\xE2\x9A\xA0 " + hrAbnormal
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
	mu.Lock()
	hr := currentHR
	conn := connected
	mu.Unlock()
	if conn && hrValid(hr) {
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

const appVersion = "3.4"

const (
	logKeepLines = 200
	logFlushWait = 200 * time.Millisecond
)

func addLog(msg string) {
	addLogForce(msg)
}

func addLogForce(msg string) {
	t := time.Now().Format("15:04:05")
	entry := fmt.Sprintf("[%s] %s", t, msg)
	logMu.Lock()
	logLines = append(logLines, entry)
	if len(logLines) > logKeepLines {
		logLines = append([]string(nil), logLines[len(logLines)-logKeepLines:]...)
	}
	logDirty = true
	if logTimer == nil {
		logTimer = time.AfterFunc(logFlushWait, flushLog)
	}
	logMu.Unlock()
}

func flushLog() {
	logMu.Lock()
	if !logDirty {
		logTimer = nil
		logMu.Unlock()
		return
	}
	lines := append([]string(nil), logLines...)
	logDirty = false
	logTimer = nil
	logMu.Unlock()
	var b strings.Builder
	for i := len(lines) - 1; i >= 0; i-- {
		b.WriteString(lines[i])
		b.WriteString("\r\n")
	}
	content := b.String()
	logContent = content
	if hwnd != nil {
		setTextW(C.GetDlgItem(hwnd, 12), truncateRunes(content, 4000))
	}
}

func truncateRunes(s string, limit int) string {
	if len(s) <= limit {
		return s
	}
	r := []rune(s)
	if len(r) <= limit {
		return s
	}
	return string(r[:limit])
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
		json.NewEncoder(w).Encode(map[string]interface{}{
			"hr": hr, "device": dev, "connected": conn,
		})
	})
	mux.HandleFunc("/api/info", func(w http.ResponseWriter, r *http.Request) {
		tip := getLocalIP()
		port := localPort
		mu.Lock()
		hr, dev, conn := currentHR, deviceName, connected
		mode := "本地"
		if mqttActive {
			mode = "MQTT"
		}
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-cache")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"ip":        tip,
			"port":      port,
			"pushUrl":   fmt.Sprintf("http://%s:%d/api/hr", tip, port),
			"obsUrl":    fmt.Sprintf("http://%s:%d/?transparent=1", tip, port),
			"mode":      mode,
			"hr":        hr,
			"device":    dev,
			"connected": conn,
			"version":   appVersion,
		})
	})
	mux.HandleFunc("/api/sse", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")
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
.row{display:flex;gap:8px;align-items:center}
.row .val{flex:1;min-width:0}
.cpy{background:#2a2a34;color:#ccc;border:none;border-radius:8px;padding:6px 10px;font-size:12px;cursor:pointer;white-space:nowrap}
.cpy:active{background:#3a3a44}
.cpy.ok{background:#2e5c3a;color:#8ee6a0}
.footer{text-align:center;font-size:11px;color:#4a3a40;padding:12px}
</style>
</head>
<body>
<h1>❤️ 心迹</h1>
<div class="card">
<div class="hr-num" id="hrV">--</div>
<div class="hr-unit">BPM</div>
<div class="status"><span class="dot off" id="dot"></span><span id="st">等待数据</span></div>
</div>
<div class="card">
<div class="info">📥 手机推送地址</div>
<div class="row"><div class="val" id="pushUrl">正在获取...</div><button class="cpy" data-t="pushUrl">复制</button></div>
<div style="margin-top:8px" class="info">📺 OBS 叠加</div>
<div class="row"><div class="val" id="obsUrl">正在获取...</div><button class="cpy" data-t="obsUrl">复制</button></div>
</div>
<div class="card">
<div class="info">📡 运行信息</div>
<div class="info" id="logArea" style="margin-top:4px;line-height:1.6">正在获取...</div>
</div>
<div class="footer">❤️ 心迹 · PC 心率监测</div>
<script>
var HR_MIN=20,HR_MAX=250;
function hrValid(v){return typeof v==='number'&&v>=HR_MIN&&v<=HR_MAX;}
var HR_ABNORMAL='数值异常';
var T_CONNECTED='已连接',T_WAIT='等待数据',T_OFF='连接断开',T_STALE='数据已过期';
var last=0,es=null,infoEl=document.getElementById('logArea');

function setHr(hr){
var el=document.getElementById('hrV'),dot=document.getElementById('dot'),st=document.getElementById('st');
if(hr===null||hr===undefined){el.innerText='--';el.style.color='#443a40';dot.className='dot off';st.innerText=T_OFF;return;}
if(hrValid(hr)){el.innerText=hr;el.style.color='#ff5d7c';dot.className='dot on';}
else{el.innerText=HR_ABNORMAL;el.style.color='#ffa726';dot.className='dot on';}
}

function tick(){
if(!last)return;
var st=document.getElementById('st'),dot=document.getElementById('dot');
if(Date.now()-last>10000){dot.className='dot off';st.innerText=T_STALE;document.getElementById('hrV').style.color='#443a40';}
}

function loadInfo(){
fetch('/api/info',{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){
document.getElementById('pushUrl').innerText=d.pushUrl||'--';
document.getElementById('obsUrl').innerText=d.obsUrl||'--';
infoEl.innerText='运行模式：'+(d.mode||'--')+'　版本：'+(d.version||'--')+'　本机：'+(d.ip||'--')+':'+(d.port||'--');
if(d.hr!==undefined)setHr(d.connected&&d.hr>0?d.hr:null);
}).catch(function(){infoEl.innerText='运行信息获取失败';});
}

function connect(){
if(es)es.close();
last=0;
es=new EventSource('/api/sse');
es.onmessage=function(e){
var d;try{d=JSON.parse(e.data);}catch(err){return;}
last=Date.now();
var st=document.getElementById('st'),dot=document.getElementById('dot');
if(d.connected&&hrValid(d.hr)){setHr(d.hr);st.innerText=T_CONNECTED+'：'+(d.device||'');dot.className='dot on';}
else if(d.connected&&d.hr>0){setHr(d.hr);st.innerText=T_CONNECTED+'：'+(d.device||'');dot.className='dot on';}
else if(d.connected){setHr(null);st.innerText=T_WAIT;dot.className='dot on';}
else{setHr(null);st.innerText=T_OFF;dot.className='dot off';}
};
es.onerror=function(){last=0;document.getElementById('st').innerText=T_OFF+'，正在重连...';document.getElementById('dot').className='dot off';};
}

document.querySelectorAll('.cpy').forEach(function(b){
b.addEventListener('click',function(){
var t=document.getElementById(b.getAttribute('data-t')).innerText;
var done=function(){var o=b.innerText;b.innerText='已复制 ✓';b.className='cpy ok';setTimeout(function(){b.innerText=o;b.className='cpy';},1400);};
if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t).then(done).catch(done);}
else{var ta=document.createElement('textarea');ta.value=t;document.body.appendChild(ta);ta.select();try{document.execCommand('copy');}catch(err){}document.body.removeChild(ta);done();}
});
});

loadInfo();
connect();
setInterval(tick,1000);
</script>
</body>
</html>`
