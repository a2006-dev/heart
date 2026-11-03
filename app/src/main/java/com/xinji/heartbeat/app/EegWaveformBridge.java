package com.xinji.heartbeat.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.xinji.heartbeat.core.HeartRateManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

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
