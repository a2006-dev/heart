package com.xinji.heartbeat.data.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HeartRecord {
    public long startTime;
    public long endTime;
    public List<Integer> hrValues;

    public HeartRecord(long startTime, long endTime, List<Integer> hrValues) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.hrValues = hrValues;
    }

    public long getDurationMs() { return endTime - startTime; }

    public int getMinHR() {
        int min = Integer.MAX_VALUE;
        for (int v : hrValues) if (v < min) min = v;
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    public int getMaxHR() {
        int max = 0;
        for (int v : hrValues) if (v > max) max = v;
        return max;
    }

    public int getAvgHR() {
        if (hrValues.isEmpty()) return 0;
        long sum = 0;
        for (int v : hrValues) sum += v;
        return (int)(sum / hrValues.size());
    }

    public String getStartTimeStr() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(startTime));
    }

    public String getEndTimeStr() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(endTime));
    }

    public String getDurationStr() {
        long secs = getDurationMs() / 1000;
        return (secs / 60) + "分" + (secs % 60) + "秒";
    }

    public String getDateLabel() {
        return new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date(startTime));
    }

    public static HeartRecord fromPrefValue(String value) {
        String[] parts = value.split("\\|", 3);
        if (parts.length < 3) return null;
        try {
            long s = Long.parseLong(parts[0]);
            long e = Long.parseLong(parts[1]);
            List<Integer> vals = new ArrayList<>();
            for (String v : parts[2].split(",")) {
                try { vals.add(Integer.parseInt(v.trim())); } catch (Exception ignored) {}
            }
            if (vals.isEmpty()) return null;
            return new HeartRecord(s, e, vals);
        } catch (Exception e) {
            return null;
        }
    }

    public String toPrefValue() {
        StringBuilder sb = new StringBuilder();
        sb.append(startTime).append("|").append(endTime).append("|");
        for (int i = 0; i < hrValues.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(hrValues.get(i));
        }
        return sb.toString();
    }

    public List<Integer> getSampled(int maxPoints) {
        if (hrValues.size() <= maxPoints) return new ArrayList<>(hrValues);
        int step = hrValues.size() / maxPoints;
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < hrValues.size(); i += step) {
            int sum = 0, count = 0;
            for (int j = i; j < i + step && j < hrValues.size(); j++) {
                sum += hrValues.get(j);
                count++;
            }
            result.add(count > 0 ? sum / count : hrValues.get(i));
        }
        return result;
    }
}
