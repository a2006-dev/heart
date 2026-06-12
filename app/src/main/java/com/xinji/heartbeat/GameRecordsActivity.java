package com.xinji.heartbeat;
import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.*;
import com.github.mikephil.charting.data.*;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.xinji.heartbeat.data.model.HeartRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class GameRecordsActivity extends AppCompatActivity {
    private LineChart lineChart;
    private TextView tvStartTime, tvEndTime, tvDuration, tvMaxHR, tvMinHR, tvAvgHR;
    private ImageButton btnShare;
    private HeartRecord record;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        // 强制隐藏状态栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        setContentView(R.layout.activity_game_records);

        String recordStr = getIntent().getStringExtra("record");
        if (recordStr == null || recordStr.isEmpty()) {
            Toast.makeText(this, "无记录数据", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        record = HeartRecord.fromPrefValue(recordStr);
        if (record == null) {
            Toast.makeText(this, "记录解析失败", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        lineChart = findViewById(R.id.lineChart);
        tvStartTime = findViewById(R.id.tvStartTime);
        tvEndTime = findViewById(R.id.tvEndTime);
        tvDuration = findViewById(R.id.tvDuration);
        tvMaxHR = findViewById(R.id.tvMaxHR);
        tvMinHR = findViewById(R.id.tvMinHR);
        tvAvgHR = findViewById(R.id.tvAvgHR);
        btnShare = findViewById(R.id.btnShare);

        tvStartTime.setText("开始时间：" + record.getStartTimeStr());
        tvEndTime.setText("结束时间：" + record.getEndTimeStr());
        tvDuration.setText("时长：" + record.getDurationStr());
        tvMaxHR.setText("最高心率：" + record.getMaxHR() + " BPM");
        tvMinHR.setText("最低心率：" + record.getMinHR() + " BPM");
        tvAvgHR.setText("平均心率：" + record.getAvgHR() + " BPM");

        setupChart();
        btnShare.setOnClickListener(v -> shareRecord());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        ((TextView)findViewById(R.id.tvAppName)).setText("心率记录");
    }

    private void setupChart() {
        List<Integer> sampled = record.getSampled(200);
        List<Entry> entries = new ArrayList<>();
        long interval = record.getDurationMs() / Math.max(1, sampled.size());
        for (int i = 0; i < sampled.size(); i++) {
            entries.add(new Entry(i, sampled.get(i)));
        }

        LineDataSet dataSet = new LineDataSet(entries, "心率");
        dataSet.setColor(Color.parseColor("#FF5D7C"));
        dataSet.setLineWidth(2f);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(Color.parseColor("#33FF5D7C"));
        dataSet.setFillAlpha(80);
        dataSet.setHighlightEnabled(true);
        dataSet.setHighLightColor(Color.parseColor("#66FF5D7C"));

        lineChart.setData(new LineData(dataSet));
        lineChart.setBackgroundColor(Color.TRANSPARENT);
        lineChart.setGridBackgroundColor(Color.TRANSPARENT);
        lineChart.getLegend().setEnabled(false);
        lineChart.getDescription().setEnabled(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);
        lineChart.setDoubleTapToZoomEnabled(false);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(Color.parseColor("#1AFFFFFF"));
        xAxis.setGridLineWidth(0.5f);
        xAxis.setDrawAxisLine(false);
        xAxis.setTextColor(Color.parseColor("#675c62"));
        xAxis.setTextSize(10f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int idx = Math.round(value);
                if (idx < 0 || idx >= sampled.size()) return "";
                long time = record.startTime + (long)(idx * interval);
                return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(time));
            }
        });

        YAxis yAxisLeft = lineChart.getAxisLeft();
        yAxisLeft.setTextColor(Color.parseColor("#675c62"));
        yAxisLeft.setTextSize(10f);
        yAxisLeft.setAxisMinimum(40f);
        yAxisLeft.setAxisMaximum(200f);
        yAxisLeft.setDrawGridLines(true);
        yAxisLeft.setGridColor(Color.parseColor("#1AFFFFFF"));
        yAxisLeft.setGridLineWidth(0.5f);
        yAxisLeft.setDrawAxisLine(false);

        YAxis yAxisRight = lineChart.getAxisRight();
        yAxisRight.setEnabled(false);

        HRMarkerView mv = new HRMarkerView(this, record.startTime, interval, sampled.size());
        mv.setChartView(lineChart);
        lineChart.setMarker(mv);
        lineChart.invalidate();
    }

    private void shareRecord() {
        try {
            Bitmap chartBitmap = getChartBitmap();
            Bitmap combined = createCombinedBitmap(chartBitmap);
            chartBitmap.recycle();

            File shareDir = new File(getCacheDir(), "share");
            if (!shareDir.exists()) shareDir.mkdirs();
            File imageFile = new File(shareDir, "heart_record_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(imageFile);
            combined.compress(Bitmap.CompressFormat.PNG, 80, fos);
            fos.close();

            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", imageFile);
            } else {
                uri = Uri.fromFile(imageFile);
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_TEXT, "❤️ 心率记录\n" + record.getStartTimeStr() + " → " + record.getEndTimeStr() + "\n" + record.getDurationStr() + "  最高" + record.getMaxHR() + " 最低" + record.getMinHR() + " 平均" + record.getAvgHR());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "分享心率记录"));
            combined.recycle();
        } catch (Exception e) {
            Toast.makeText(this, "分享失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap getChartBitmap() {
        try {
            lineChart.setDrawingCacheEnabled(true);
            lineChart.buildDrawingCache();
            Bitmap cache = lineChart.getDrawingCache();
            if (cache != null) {
                Bitmap bitmap = Bitmap.createBitmap(cache);
                lineChart.setDrawingCacheEnabled(false);
                return bitmap;
            }
        } catch (Exception ignored) {}
        lineChart.setDrawingCacheEnabled(false);
        Bitmap fallback = Bitmap.createBitmap(dp2px(300), dp2px(200), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(fallback);
        canvas.drawColor(Color.parseColor("#0c0c10"));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#675c62"));
        p.setTextSize(dp2px(16));
        canvas.drawText("图表加载失败", dp2px(80), dp2px(100), p);
        return fallback;
    }

    private Bitmap createCombinedBitmap(Bitmap chartBitmap) {
        int width = Math.max(chartBitmap.getWidth() + 100, dp2px(350));
        int textHeight = dp2px(220);
        int totalHeight = textHeight + chartBitmap.getHeight() + 100;
        Bitmap combined = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(combined);
        canvas.drawColor(Color.parseColor("#0c0c10"));

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.parseColor("#ff5d7c"));
        titlePaint.setTextSize(dp2px(22));
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#e2c2cf"));
        textPaint.setTextSize(dp2px(18));
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setColor(Color.parseColor("#675c62"));
        subPaint.setTextSize(dp2px(14));

        int x = 50, y = 55;
        canvas.drawText("❤️ 心迹 · 心率记录", x, y, titlePaint);
        y += dp2px(38);
        canvas.drawText("时长：" + record.getDurationStr(), x, y, textPaint);
        y += dp2px(30);
        canvas.drawText(record.getStartTimeStr() + " → " + record.getEndTimeStr(), x, y, subPaint);
        y += dp2px(30);
        canvas.drawText("❤ 最高 " + record.getMaxHR() + "  BPM", x, y, textPaint);
        y += dp2px(28);
        canvas.drawText("💚 最低 " + record.getMinHR() + "  BPM", x, y, textPaint);
        y += dp2px(28);
        canvas.drawText("⏱ 平均 " + record.getAvgHR() + "  BPM", x, y, textPaint);
        y += dp2px(28);
        canvas.drawText("📊 共 " + record.hrValues.size() + " 个数据点", x, y, subPaint);

        int chartY = textHeight + 50;
        canvas.drawBitmap(chartBitmap, 50, chartY, null);
        return combined;
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
