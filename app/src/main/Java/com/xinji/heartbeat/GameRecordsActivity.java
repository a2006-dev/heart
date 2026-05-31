package com.xinji.heartbeat;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.*;
import com.github.mikephil.charting.data.*;
import com.github.mikephil.charting.formatter.ValueFormatter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
public class GameRecordsActivity extends AppCompatActivity {
    private LineChart lineChart;
    private TextView tvAppName, tvStartTime, tvEndTime, tvDuration, tvMaxHR, tvMinHR;
    private ImageButton btnShare;
    private String packageName, appName;
    private SharedPreferences recordPrefs;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_records);
        packageName = getIntent().getStringExtra("packageName");
        appName = getIntent().getStringExtra("appName");
        recordPrefs = getSharedPreferences("game_records", Context.MODE_PRIVATE);
        lineChart = findViewById(R.id.lineChart);
        tvAppName = findViewById(R.id.tvAppName);
        tvStartTime = findViewById(R.id.tvStartTime);
        tvEndTime = findViewById(R.id.tvEndTime);
        tvDuration = findViewById(R.id.tvDuration);
        tvMaxHR = findViewById(R.id.tvMaxHR);
        tvMinHR = findViewById(R.id.tvMinHR);
        btnShare = findViewById(R.id.btnShare);
        tvAppName.setText(appName);
        loadRecordData();
        btnShare.setOnClickListener(v -> shareRecord());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }
    private void loadRecordData() {
        String recordKey = packageName + "_latest";
        String recordData = recordPrefs.getString(recordKey, "");
        if (recordData == null || recordData.isEmpty()) {
            Toast.makeText(this, "暂无记录数据", Toast.LENGTH_SHORT).show();
            setupEmptyChart();
            return;
        }
        String[] parts = recordData.split("\\|");
        if (parts.length < 3) {
            setupEmptyChart();
            return;
        }
        long startTime, endTime;
        try {
            startTime = Long.parseLong(parts[0]);
            endTime = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            setupEmptyChart();
            return;
        }
        String[] hrValues = parts[2].split(",");
        List<Integer> hrList = new ArrayList<>();
        int maxHR = 0, minHR = 200;
        int maxPoints = 600; // 最多保留600个点（10分钟 @ 1次/秒）
        int step = Math.max(1, hrValues.length / maxPoints);
        for (int i = 0; i < hrValues.length; i += step) {
            String hr = hrValues[i];
            if (!hr.isEmpty()) {
                try {
                    int val = Integer.parseInt(hr);
                    hrList.add(val);
                    if (val > maxHR) maxHR = val;
                    if (val < minHR) minHR = val;
                } catch (NumberFormatException ignored) {}
            }
        }
        long duration = endTime - startTime;
        tvStartTime.setText("开始时间：" + formatTime(startTime));
        tvEndTime.setText("结束时间：" + formatTime(endTime));
        tvDuration.setText("时长：" + formatDuration(duration));
        tvMaxHR.setText("最高心率：" + maxHR + " BPM");
        tvMinHR.setText("最低心率：" + minHR + " BPM");
        setupChart(hrList, startTime, endTime);
    }
    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return String.format(Locale.getDefault(), "%d分%d秒", minutes, secs);
    }
    private void setupEmptyChart() {
        lineChart.getDescription().setText("暂无数据");
        lineChart.getXAxis().setDrawLabels(false);
        lineChart.getAxisLeft().setDrawLabels(false);
        lineChart.getAxisRight().setDrawLabels(false);
        lineChart.getLegend().setEnabled(false);
        lineChart.invalidate();
    }
    private void setupChart(List<Integer> hrList, long startTime, long endTime) {
        List<Integer> sampled = new ArrayList<>();
        int step = Math.max(1, hrList.size() / 200);
        for (int i = 0; i < hrList.size(); i += step) {
            int sum = 0, count = 0;
            for (int j = i; j < i + step && j < hrList.size(); j++) {
                sum += hrList.get(j); count++;
            }
            sampled.add(count > 0 ? sum / count : hrList.get(i));
        }
        List<Entry> entries = new ArrayList<>();
        long interval = (endTime - startTime) / Math.max(1, sampled.size());
        for (int i = 0; i < sampled.size(); i++) {
            entries.add(new Entry(i, sampled.get(i)));
        }
        int lineColor = Color.parseColor("#FF5D7C");
        int fillColor = Color.parseColor("#33FF5D7C");
        LineDataSet dataSet = new LineDataSet(entries, "心率");
        dataSet.setColor(lineColor);
        dataSet.setLineWidth(2f);
        dataSet.setDrawCircles(false);   // 不要点点
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(fillColor);
        dataSet.setFillAlpha(80);
        LineData lineData = new LineData(dataSet);
        lineChart.setData(lineData);
        lineChart.setBackgroundColor(Color.TRANSPARENT);
        lineChart.setGridBackgroundColor(Color.TRANSPARENT);
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
                long time = startTime + (long)(idx * interval);
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
        yAxisLeft.setDrawZeroLine(false);
        YAxis yAxisRight = lineChart.getAxisRight();
        yAxisRight.setEnabled(false);
        Legend legend = lineChart.getLegend();
        legend.setEnabled(false);
        lineChart.getDescription().setEnabled(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);
        lineChart.setDoubleTapToZoomEnabled(false);
        lineChart.setHighlightPerDragEnabled(true);
        lineChart.setHighlightPerTapEnabled(true);
        lineChart.getRenderer().getPaintRender().setColor(Color.parseColor("#66FF5D7C"));
        HRMarkerView mv = new HRMarkerView(this, startTime, interval, sampled.size());
        mv.setChartView(lineChart);
        lineChart.setMarker(mv);
        dataSet.setHighlightEnabled(true);
        dataSet.setHighLightColor(Color.parseColor("#66FF5D7C"));
        dataSet.setDrawHorizontalHighlightIndicator(true);
        dataSet.setDrawVerticalHighlightIndicator(true);
        lineChart.invalidate();
    }
    private void shareRecord() {
        try {
            Bitmap chartBitmap = getChartBitmap();
            Bitmap combinedBitmap = createCombinedBitmap(chartBitmap);
            File shareDir = new File(getCacheDir(), "share");
            if (!shareDir.exists()) shareDir.mkdirs();
            File imageFile = new File(shareDir, "heart_record_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(imageFile);
            combinedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
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
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "分享心率记录"));
        } catch (Exception e) {
            e.printStackTrace();
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
        int width = chartBitmap.getWidth() + 100;
        int textHeight = dp2px(180);
        int totalHeight = textHeight + chartBitmap.getHeight() + 100;
        Bitmap combined = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(combined);
        canvas.drawColor(Color.parseColor("#0c0c10"));
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#e2c2cf"));
        textPaint.setTextSize(dp2px(18));
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subTextPaint.setColor(Color.parseColor("#675c62"));
        subTextPaint.setTextSize(dp2px(14));
        int x = 50;
        int y = 60;
        canvas.drawText(appName, x, y, textPaint);
        y += dp2px(35);
        canvas.drawText(tvStartTime.getText().toString(), x, y, subTextPaint);
        y += dp2px(28);
        canvas.drawText(tvEndTime.getText().toString(), x, y, subTextPaint);
        y += dp2px(28);
        canvas.drawText(tvDuration.getText().toString(), x, y, subTextPaint);
        y += dp2px(28);
        canvas.drawText(tvMaxHR.getText().toString(), x, y, textPaint);
        y += dp2px(28);
        canvas.drawText(tvMinHR.getText().toString(), x, y, textPaint);
        int chartY = textHeight + 50;
        canvas.drawBitmap(chartBitmap, 50, chartY, null);
        return combined;
    }
    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
