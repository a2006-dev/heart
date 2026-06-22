package com.xinji.heartbeat.core;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 心率数据管理中心。
 * <p>
 * 解耦设计：
 * - 通过 HeartRateListener 通知 UI 层心率变更
 * - 通过 EegDataListener 通知 ECG 波形消费者
 * - RR 间期缓存：最多保留最近 100 个，支持 HRV 计算
 * - 30 秒心率滑动窗口：用于计算平均心率
 * - 无 RR 数据时自动降级为基于心率的仿真波形参数
 */
public class HeartRateManager {
    private static volatile HeartRateManager instance;
    private Context context;
    private final CopyOnWriteArrayList<WeakReference<HeartRateListener>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<WeakReference<EegDataListener>> eegListeners = new CopyOnWriteArrayList<>();

    private volatile int currentHR = 0;
    private volatile long lastHRTime = 0;
    private Handler timeoutHandler;
    private TimeoutListener timeoutListener;
    private static final long TIMEOUT_MS = 5 * 60 * 1000;

    // RR 间期缓存 (单位: 1/1024 秒)
    private static final int MAX_RR_HISTORY = 100;
    private final LinkedList<Integer> rrHistory = new LinkedList<>();

    // 最近一次收到的原始 RR 数据
    private volatile int[] lastRRIntervals = null;

    // 30 秒心率滑动窗口 (记录 {时间戳, 心率})
    private static final long AVG_HR_WINDOW_MS = 30 * 1000;
    private final LinkedList<long[]> hrHistory = new LinkedList<>(); // [timestamp, hr]

    public interface HeartRateListener {
        void onHeartRateChanged(int hr);
    }

    /** ECG 波形数据消费者接口 */
    public interface EegDataListener {
        void onEegData(int hr, int rrMs, int avgRRMs, int sdnnMs);
    }

    public interface TimeoutListener {
        void onHeartRateTimeout();
    }

    private HeartRateManager(Context context) {
        this.context = context.getApplicationContext();
        this.timeoutHandler = new Handler(Looper.getMainLooper());
    }

    public static HeartRateManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HeartRateManager.class) {
                if (instance == null) {
                    instance = new HeartRateManager(context);
                }
            }
        }
        return instance;
    }

    public int getCurrentHR() {
        return currentHR;
    }

    /** 计算 30 秒内平均心率，-1 表示数据不足 */
    public synchronized int getAvgHR() {
        long now = System.currentTimeMillis();
        long cutoff = now - AVG_HR_WINDOW_MS;
        int sum = 0, count = 0;
        for (int i = hrHistory.size() - 1; i >= 0; i--) {
            long[] entry = hrHistory.get(i);
            if (entry[0] >= cutoff) {
                sum += (int) entry[1];
                count++;
            }
        }
        return count >= 2 ? Math.round((float) sum / count) : -1;
    }

    public long getLastHRTime() {
        return lastHRTime;
    }

    public synchronized int[] getRRHistory() {
        int[] arr = new int[rrHistory.size()];
        int i = 0;
        for (int rr : rrHistory) arr[i++] = rr;
        return arr;
    }

    public int[] getLastRRIntervals() {
        return lastRRIntervals;
    }

    public synchronized int getSDNN() {
        int n = rrHistory.size();
        if (n < 3) return -1;
        double sum = 0;
        for (int rr : rrHistory) sum += rr;
        double mean = sum / n;
        double variance = 0;
        for (int rr : rrHistory) {
            double diff = rr - mean;
            variance += diff * diff;
        }
        variance /= n;
        return (int) Math.round(Math.sqrt(variance));
    }

    public synchronized int getAvgRRMs() {
        if (rrHistory.isEmpty()) return -1;
        if (currentHR > 0) return 60000 / currentHR;
        int sum = 0;
        for (int rr : rrHistory) sum += rr;
        return sum / rrHistory.size();
    }

    public void setTimeoutListener(TimeoutListener listener) {
        this.timeoutListener = listener;
    }

    public void startTimeoutCheck() {
        timeoutHandler.removeCallbacksAndMessages(null);
        timeoutHandler.postDelayed(timeoutCheck, TIMEOUT_MS);
    }

    public void stopTimeoutCheck() {
        timeoutHandler.removeCallbacksAndMessages(null);
    }

    private final Runnable timeoutCheck = () -> {
        if (lastHRTime > 0 && System.currentTimeMillis() - lastHRTime >= TIMEOUT_MS) {
            if (timeoutListener != null) {
                timeoutListener.onHeartRateTimeout();
            }
        }
    };

    public void registerListener(HeartRateListener listener) {
        if (listener == null) return;
        for (WeakReference<HeartRateListener> ref : listeners) {
            HeartRateListener existing = ref.get();
            if (existing == listener) return;
        }
        listeners.add(new WeakReference<>(listener));
    }

    public void removeListener(HeartRateListener listener) {
        Iterator<WeakReference<HeartRateListener>> it = listeners.iterator();
        while (it.hasNext()) {
            HeartRateListener l = it.next().get();
            if (l == null || l == listener) {
                it.remove();
            }
        }
    }

    public void registerEegListener(EegDataListener listener) {
        if (listener == null) return;
        for (WeakReference<EegDataListener> ref : eegListeners) {
            EegDataListener existing = ref.get();
            if (existing == listener) return;
        }
        eegListeners.add(new WeakReference<>(listener));
    }

    public void removeEegListener(EegDataListener listener) {
        Iterator<WeakReference<EegDataListener>> it = eegListeners.iterator();
        while (it.hasNext()) {
            EegDataListener l = it.next().get();
            if (l == null || l == listener) {
                it.remove();
            }
        }
    }

    @Deprecated
    public void removeAllListeners() {
        listeners.clear();
    }

    public void notifyListeners(int hr) {
        notifyListeners(hr, null);
    }

    public void notifyListeners(int hr, int[] rrIntervals) {
        this.currentHR = hr;
        if (hr > 20 && hr < 250) {
            this.lastHRTime = System.currentTimeMillis();
        }

        // 记录心率到30秒滑动窗口
        if (hr > 20 && hr < 250) {
            long now = System.currentTimeMillis();
            synchronized (this) {
                hrHistory.addLast(new long[]{now, hr});
                long cutoff = now - AVG_HR_WINDOW_MS;
                while (!hrHistory.isEmpty() && hrHistory.getFirst()[0] < cutoff) {
                    hrHistory.removeFirst();
                }
            }
        }

        int rrMs = -1;
        if (rrIntervals != null && rrIntervals.length > 0) {
            this.lastRRIntervals = rrIntervals;
            synchronized (this) {
                for (int rr : rrIntervals) {
                    int rrMsVal = rr * 1000 / 1024;
                    if (rrMsVal > 200 && rrMsVal < 3000) {
                        rrHistory.addLast(rrMsVal);
                        if (rrHistory.size() > MAX_RR_HISTORY) {
                            rrHistory.removeFirst();
                        }
                        rrMs = rrMsVal;
                    }
                }
            }
        } else {
            this.lastRRIntervals = null;
        }

        Iterator<WeakReference<HeartRateListener>> it = listeners.iterator();
        while (it.hasNext()) {
            HeartRateListener listener = it.next().get();
            if (listener == null) {
                it.remove();
                continue;
            }
            try {
                listener.onHeartRateChanged(hr);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        int avgRR = getAvgRRMs();
        int sdnn = getSDNN();
        Iterator<WeakReference<EegDataListener>> eit = eegListeners.iterator();
        while (eit.hasNext()) {
            EegDataListener el = eit.next().get();
            if (el == null) {
                eit.remove();
                continue;
            }
            try {
                el.onEegData(hr, rrMs, avgRR, sdnn);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
