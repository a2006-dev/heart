package com.xinji.heartbeat;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.xinji.heartbeat.core.HeartRateManager;

import java.util.ArrayList;
import java.util.List;

public class GameModeFragment extends Fragment {
    private MainActivity activity;
    private SharedPreferences gamePrefs;
    private Button btnStartRecord, btnStopRecord;
    private TextView tvRecordStatus;
    private boolean isRecording = false;
    private long recordStartTime = 0;
    private final List<Integer> recordData = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private HeartRateManager.HeartRateListener hrListener;
    private RecyclerView recordsList;
    private RecordsAdapter recordsAdapter;
    private final List<RecordItem> records = new ArrayList<>();

    static class RecordItem {
        long startTime, endTime;
        String data; // 逗号分隔的心率值
        RecordItem(long s, long e, String d) { startTime = s; endTime = e; data = d; }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_game_mode, container, false);
        activity = (MainActivity) getActivity();
        gamePrefs = activity.getSharedPreferences("game_mode", Context.MODE_PRIVATE);

        btnStartRecord = v.findViewById(R.id.btnStartRecord);
        btnStopRecord = v.findViewById(R.id.btnStopRecord);
        tvRecordStatus = v.findViewById(R.id.tvRecordStatus);
        recordsList = v.findViewById(R.id.recordsList);
        recordsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        recordsAdapter = new RecordsAdapter();
        recordsList.setAdapter(recordsAdapter);

        btnStartRecord.setOnClickListener(view -> startRecording());
        btnStopRecord.setOnClickListener(view -> stopRecording());
        btnStopRecord.setEnabled(false);

        // 隐藏不需要的控件
        v.findViewById(R.id.swShowSystem).setVisibility(View.GONE);
        v.findViewById(R.id.btnAddGame).setVisibility(View.GONE);
        v.findViewById(R.id.tvEmpty).setVisibility(View.GONE);
        ((TextView)v.findViewById(R.id.tvWindowModeHint)).setVisibility(View.GONE);

        // 心率监听
        hrListener = hr -> {
            if (isRecording && hr > 30 && hr < 220) {
                recordData.add(hr);
            }
        };
        HeartRateManager.getInstance(requireContext()).registerListener(hrListener);

        loadRecords();
        return v;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (hrListener != null) HeartRateManager.getInstance(requireContext()).removeListener(hrListener);
        handler.removeCallbacksAndMessages(null);
    }

    private void startRecording() {
        if (isRecording) return;
        isRecording = true;
        recordStartTime = System.currentTimeMillis();
        recordData.clear();
        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        btnStartRecord.setText("🔴 记录中");
        tvRecordStatus.setText("正在记录...");
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        long end = System.currentTimeMillis();
        long dur = end - recordStartTime;
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);
        btnStartRecord.setText("⏺ 开始记录");

        if (dur >= 3 * 60 * 1000 && recordData.size() >= 10) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < recordData.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(recordData.get(i));
            }
            String key = "record_" + recordStartTime;
            gamePrefs.edit().putString(key, recordStartTime + "|" + end + "|" + sb.toString()).apply();
            tvRecordStatus.setText("✅ 已保存（" + (dur / 60000) + "分" + (dur % 60000 / 1000) + "秒）");
            loadRecords();
        } else {
            tvRecordStatus.setText("⏳ 不足3分钟，未保存");
        }
        handler.postDelayed(() -> tvRecordStatus.setText(""), 3000);
    }

    private void loadRecords() {
        records.clear();
        for (String key : gamePrefs.getAll().keySet()) {
            if (!key.startsWith("record_")) continue;
            String val = gamePrefs.getString(key, "");
            if (val.isEmpty()) continue;
            String[] parts = val.split("\\|", 3);
            if (parts.length == 3) {
                try {
                    long s = Long.parseLong(parts[0]);
                    long e = Long.parseLong(parts[1]);
                    records.add(new RecordItem(s, e, parts[2]));
                } catch (Exception ignored) {}
            }
        }
        // 按时间倒序
        records.sort((a, b) -> Long.compare(b.startTime, a.startTime));
        // 只保留最近20条
        while (records.size() > 20) records.remove(records.size() - 1);
        recordsAdapter.notifyDataSetChanged();
    }

    class RecordsAdapter extends RecyclerView.Adapter<RecordsAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvInfo; ImageView chart;
            ViewHolder(View v) { super(v); tvInfo = v.findViewById(R.id.recordInfo); chart = v.findViewById(R.id.recordChart); }
        }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(requireContext()).inflate(R.layout.item_record, p, false);
            return new ViewHolder(v);
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            RecordItem r = records.get(pos);
            long dur = r.endTime - r.startTime;
            String[] vals = r.data.split(",");
            int minHr = 255, maxHr = 0, avg = 0;
            for (String s : vals) {
                try { int v = Integer.parseInt(s.trim()); if (v < minHr) minHr = v; if (v > maxHr) maxHr = v; avg += v; } catch (Exception ignored) {}
            }
            avg = vals.length > 0 ? avg / vals.length : 0;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault());
            h.tvInfo.setText(sdf.format(new java.util.Date(r.startTime)) + "  " + (dur / 60000) + "分" + (dur % 60000 / 1000) + "秒  " + vals.length + "条  ❤" + minHr + "-" + maxHr + "  ⏱" + avg);

            // 绘制迷你折线图
            int w = h.chart.getWidth() > 0 ? h.chart.getWidth() : 400;
            int hh = 100;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(Math.max(w, 100), hh, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(0xFF0c0c10);
            Paint line = new Paint(); line.setColor(0xFFFF5D7C); line.setStrokeWidth(2); line.setStyle(Paint.Style.STROKE);
            Paint dot = new Paint(); dot.setColor(0xFFFF5D7C); dot.setStyle(Paint.Style.FILL);
            if (vals.length > 1) {
                int[] ints = new int[vals.length];
                int mn = 255, mx = 0;
                for (int i = 0; i < vals.length; i++) {
                    ints[i] = Integer.parseInt(vals[i].trim());
                    if (ints[i] < mn) mn = ints[i];
                    if (ints[i] > mx) mx = ints[i];
                }
                int range = Math.max(mx - mn, 10);
                float stepX = (float)(w - 20) / (ints.length - 1);
                Path path = new Path();
                for (int i = 0; i < ints.length; i++) {
                    float x = 10 + i * stepX;
                    float y = 90 - (float)(ints[i] - mn) / range * 70;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                c.drawPath(path, line);
                // 最后一个点
                float lx = 10 + (ints.length - 1) * stepX;
                float ly = 90 - (float)(ints[ints.length - 1] - mn) / range * 70;
                c.drawCircle(lx, ly, 3, dot);
            }
            h.chart.setImageBitmap(bmp);
        }
        @Override public int getItemCount() { return records.size(); }
    }
}