package com.xinji.heartbeat.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.xinji.heartbeat.core.HeartRateManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * ECG 波形桥接层 — 连接 HeartRateManager.EegDataListener 与前端 WebView。
 * <p>
 * 解耦设计：
 * - 纯桥接，不依赖任何 Fragment/Activity
 * - 通过 WeakReference 持有 WebView，避免内存泄漏
 * - 数据序列化为 JSON，通过 evaluateJavascript 传入前端
 * - 前端无需关心数据来源（蓝牙真实数据 或 仿真数据）
 */
public class EegWaveformBridge implements HeartRateManager.EegDataListener {

    private final WeakReference<WebView> webViewRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public EegWaveformBridge(WebView webView) {
        this.webViewRef = new WeakReference<>(webView);
    }

    @Override
    public void onEegData(int hr, int rrMs, int avgRRMs, int sdnnMs) {
        final WebView wv = webViewRef.get();
        if (wv == null) return;

        try {
            JSONObject json = new JSONObject();
            json.put("hr", hr);
            json.put("rr", rrMs);
            json.put("avgRR", avgRRMs);
            json.put("sdnn", sdnnMs);

            // 30 秒平均心率
            int avgHr = HeartRateManager.getInstance(
                    wv.getContext().getApplicationContext()).getAvgHR();
            json.put("avgHr", avgHr);

            int[] rrHistory = HeartRateManager.getInstance(
                    wv.getContext().getApplicationContext()).getRRHistory();
            JSONArray rrArr = new JSONArray();
            if (rrHistory != null) {
                for (int r : rrHistory) rrArr.put(r);
            }
            json.put("rrHistory", rrArr);

            final String js = "window.onEegData(" + json.toString() + ")";
            mainHandler.post(() -> {
                WebView w = webViewRef.get();
                if (w != null) {
                    w.evaluateJavascript(js, null);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
