package com.xinji.heartbeat;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.xinji.heartbeat.app.HeartEventBus;
import com.xinji.heartbeat.app.PreferencesManager;
import com.xinji.heartbeat.data.model.HeartRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GameModeFragment extends Fragment implements HeartEventBus.EventListener {
    private PreferencesManager prefs;
    private Button btnStartRecord, btnStopRecord;
    private TextView tvRecordStatus;
    private boolean isRecording = false;
    private long recordStartTime = 0;
    private final List<Integer> recordData = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private RecyclerView recordsList;
    private RecordsAdapter recordsAdapter;
    private final List<HeartRecord> records = new ArrayList<>();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_game_mode, container, false);
        prefs = PreferencesManager.from(requireContext());

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

        v.findViewById(R.id.swShowSystem).setVisibility(View.GONE);
        v.findViewById(R.id.tvEmpty).setVisibility(View.GONE);
        ((TextView)v.findViewById(R.id.tvWindowModeHint)).setVisibility(View.GONE);

        HeartEventBus.getInstance().register(this);
        loadRecords();
        return v;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
        HeartEventBus.getInstance().unregister(this);
    }

    @Override
    public void onEvent(int type, Object data) {
        if (type == HeartEventBus.EVENT_HR_UPDATE && isRecording && data instanceof Integer) {
            int hr = (Integer) data;
            if (hr > 30 && hr < 220) {
                recordData.add(hr);
            }
        }
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
            HeartRecord record = new HeartRecord(recordStartTime, end, new ArrayList<>(recordData));
            prefs.saveManualRecord(record.startTime, record.endTime, record.toPrefValue().split("\\|", 3)[2]);
            tvRecordStatus.setText("✅ 已保存（" + record.getDurationStr() + "）");
            loadRecords();
        } else {
            tvRecordStatus.setText("⏳ 不足3分钟，未保存");
        }
        handler.postDelayed(() -> tvRecordStatus.setText(""), 3000);
    }

    private void loadRecords() {
        records.clear();
        Map<String, ?> all = prefs.getAllManualRecords();
        for (String key : all.keySet()) {
            if (!key.startsWith("record_")) continue;
            String val = (String) all.get(key);
            if (val == null || val.isEmpty()) continue;
            HeartRecord r = HeartRecord.fromPrefValue(val);
            if (r != null) records.add(r);
        }
        records.sort((a, b) -> Long.compare(b.startTime, a.startTime));
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
            HeartRecord r = records.get(pos);
            h.tvInfo.setText(r.getDateLabel() + "  " + r.getDurationStr() + "  " + r.hrValues.size() + "条  ❤" + r.getMinHR() + "-" + r.getMaxHR() + "  ⏱" + r.getAvgHR());

            drawMiniChart(h.chart, r);

            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), GameRecordsActivity.class);
                intent.putExtra("record", r.toPrefValue());
                startActivity(intent);
            });
        }
        @Override public int getItemCount() { return records.size(); }

        private void drawMiniChart(ImageView iv, HeartRecord r) {
            if (r.hrValues.size() < 2) { iv.setImageBitmap(null); return; }
            int w = Math.max(iv.getWidth(), 300);
            int hh = 80;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, hh, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(0xFF0c0c10);
            Paint line = new Paint(); line.setColor(0xFFFF5D7C); line.setStrokeWidth(2); line.setStyle(Paint.Style.STROKE);
            int min = r.getMinHR(), max = r.getMaxHR(), range = Math.max(max - min, 10);
            float stepX = (float)(w - 20) / (r.hrValues.size() - 1);
            Path path = new Path();
            for (int i = 0; i < r.hrValues.size(); i++) {
                float x = 10 + i * stepX;
                float y = hh - 10 - (float)(r.hrValues.get(i) - min) / range * (hh - 20);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            c.drawPath(path, line);
            Paint dot = new Paint(); dot.setColor(0xFFFF5D7C); dot.setStyle(Paint.Style.FILL);
            float lx = 10 + (r.hrValues.size() - 1) * stepX;
            float ly = hh - 10 - (float)(r.hrValues.get(r.hrValues.size() - 1) - min) / range * (hh - 20);
            c.drawCircle(lx, ly, 3, dot);
            iv.setImageBitmap(bmp);
        }
    }
}
