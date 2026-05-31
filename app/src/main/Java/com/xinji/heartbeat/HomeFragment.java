package com.xinji.heartbeat;
import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
public class HomeFragment extends Fragment {
    private WebView webView;
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        webView = v.findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setLongClickable(false);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
        return v;
    }
    public void updateHR(int hr) {
        if (webView != null && isAdded()) webView.evaluateJavascript("updateHeartRate(" + hr + ")", null);
    }
    public void updateDevice(String name) {
        if (webView != null && isAdded()) {
            String safe = name == null ? "⚡ 未连接" : name.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
            webView.evaluateJavascript("setDeviceName('" + safe + "')", null);
        }
    }
}
