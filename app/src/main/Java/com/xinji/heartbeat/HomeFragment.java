package com.xinji.heartbeat;

import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;

/**
 * 主页心率显示 — 使用 WebView 展示心率波形。
 * 优化：onPause 时暂停 WebView 渲染以降低内存占用。
 */
public class HomeFragment extends Fragment {
    private WebView webView;
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        webView = v.findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setRenderPriority(WebSettings.RenderPriority.LOW);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setLongClickable(false);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
        return v;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroyView();
    }

    public void updateHR(int hr) {
        if (webView != null && isAdded()) {
            webView.evaluateJavascript("updateHeartRate(" + hr + ")", null);
        }
    }
    public void updateDevice(String name) {
        if (webView != null && isAdded()) {
            // 正确方式：evaluateJavascript 本身接受 JS 代码字符串
            // JSONArray.toString() 输出带双引号的 JSON 字符串，可以直接作为 JS 字符串字面量
            String safe = name == null ? "⚡ 未连接" : name;
            String escaped = new org.json.JSONArray().put(safe).toString(); // 输出: ["O'Connor HR"]
            webView.evaluateJavascript("setDeviceName(" + escaped + ")", null);
        }
    }
}
