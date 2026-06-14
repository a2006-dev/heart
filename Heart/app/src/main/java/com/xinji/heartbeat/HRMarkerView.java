package com.xinji.heartbeat;
import android.content.Context;
import android.widget.TextView;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public class HRMarkerView extends MarkerView {
    private TextView tvHR, tvTime;
    private long startTime;
    private long interval;
    private int dataCount;
    public HRMarkerView(Context context, long startTime, long interval, int dataCount) {
        super(context, R.layout.marker_view);
        tvHR = findViewById(R.id.tvMarkerHR);
        tvTime = findViewById(R.id.tvMarkerTime);
        this.startTime = startTime;
        this.interval = interval;
        this.dataCount = dataCount;
    }
    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        int hr = (int) e.getY();
        tvHR.setText(hr + " BPM");
        int idx = Math.round(e.getX());
        if (idx >= 0 && idx < dataCount) {
            long time = startTime + (long) (idx * interval);
            tvTime.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(time)));
        } else {
            tvTime.setText("--:--");
        }
        super.refreshContent(e, highlight);
    }
    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 10f);
    }
}
