package com.xinji.heartbeat;
import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;

import com.xinji.heartbeat.app.EegWaveformBridge;
import com.xinji.heartbeat.core.HeartRateManager;

public class HomeFragment extends Fragment {
    private WebView webView;
    private EegWaveformBridge eegBridge;
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        webView = v.findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setRenderPriority(WebSettings.RenderPriority.LOW);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setLongClickable(false);
        webView.setWebViewClient(new WebViewClient());

        // ECG 波形桥接：将 HeartRateManager 的 EegDataListener 注册到前端
        eegBridge = new EegWaveformBridge(webView);
        HeartRateManager.getInstance(requireContext()).registerEegListener(eegBridge);

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
        // 取消注册 ECG 监听器
        if (eegBridge != null) {
            HeartRateManager.getInstance(requireContext()).removeEegListener(eegBridge);
            eegBridge = null;
        }
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
            String safe = name == null ? "⚡ 未连接" : name;
            String escaped = new org.json.JSONArray().put(safe).toString();
            webView.evaluateJavascript("setDeviceName(" + escaped + ")", null);
        }
    }
}
