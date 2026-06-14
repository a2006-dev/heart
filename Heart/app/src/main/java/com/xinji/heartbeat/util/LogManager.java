package com.xinji.heartbeat.util;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogManager {
    private static final int MAX_LOG_LINES = 300;
    private final List<LogEntry> logEntries = new ArrayList<>();
    private TextView logTextView;
    private ScrollView logScrollView;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private static class LogEntry {
        long time;
        String text;
        int color;
        LogEntry(long time, String text, int color) {
            this.time = time;
            this.text = text;
            this.color = color;
        }
    }

    public void bind(TextView textView, ScrollView scrollView) {
        this.logTextView = textView;
        this.logScrollView = scrollView;
    }

    public void append(String text, int color) {
        logEntries.add(new LogEntry(System.currentTimeMillis(), text, color));
        if (logEntries.size() > MAX_LOG_LINES) {
            logEntries.remove(0);
        }
        updateDisplay();
    }

    public void clear() {
        logEntries.clear();
        updateDisplay();
    }

    private void updateDisplay() {
        if (logTextView == null) return;
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        for (int i = 0; i < logEntries.size(); i++) {
            LogEntry entry = logEntries.get(i);
            String timeStr = sdf.format(new Date(entry.time));
            String line = "[" + timeStr + "] " + entry.text + "\n";
            int start = ssb.length();
            ssb.append(line);
            ssb.setSpan(new ForegroundColorSpan(entry.color), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        logTextView.setText(ssb);
        if (logScrollView != null) {
            logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    public int size() { return logEntries.size(); }
}
