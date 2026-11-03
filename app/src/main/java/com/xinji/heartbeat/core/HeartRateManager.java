package com.xinji.heartbeat.core;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.concurrent.CopyOnWriteArrayList;

public class HeartRateManager {
    private static volatile HeartRateManager instance;
    private Context context;
    private final CopyOnWriteArrayList<WeakReference<HeartRateListener>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<WeakReference<EegDataListener>> eegListeners = new CopyOnWriteArrayList<>();

    private volatile int currentHR = 0;
    private volatile long lastHRTime = 0;
    private final Handler timeoutHandler;
    private volatile TimeoutListener timeoutListener;
    private boolean timeoutCheckRunning = false;
    private static final long TIMEOUT_MS = 5 * 60 * 1000;

    private static final int MAX_RR_HISTORY = 100;
    private final LinkedList<Integer> rrHistory = new LinkedList<>();

    private volatile int[] lastRRIntervals = null;

    private static final long AVG_HR_WINDOW_MS = 30 * 1000;
    private final LinkedList<long[]> hrHistory = new LinkedList<>();

    public interface HeartRateListener {
        void onHeartRateChanged(int hr);
    }

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
        int[] rr = lastRRIntervals;
        return rr != null ? rr.clone() : null;
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
        int sum = 0;
        for (int rr : rrHistory) sum += rr;
        return sum / rrHistory.size();
    }

    public void setTimeoutListener(TimeoutListener listener) {
        this.timeoutListener = listener;
    }

    public synchronized void startTimeoutCheck() {
        timeoutCheckRunning = true;
        timeoutHandler.removeCallbacks(timeoutCheck);
        timeoutHandler.postDelayed(timeoutCheck, TIMEOUT_MS);
    }

    public synchronized void stopTimeoutCheck() {
        timeoutCheckRunning = false;
        timeoutHandler.removeCallbacks(timeoutCheck);
    }

    private final Runnable timeoutCheck = new Runnable() {
        @Override
        public void run() {
            TimeoutListener callback = timeoutListener;
            if (timeoutCheckRunning && lastHRTime > 0
                    && System.currentTimeMillis() - lastHRTime >= TIMEOUT_MS) {
                if (callback != null) callback.onHeartRateTimeout();
            }
            synchronized (HeartRateManager.this) {
                if (timeoutCheckRunning) {
                    timeoutHandler.postDelayed(this, TIMEOUT_MS);
                }
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
        for (WeakReference<HeartRateListener> ref : listeners) {
            HeartRateListener l = ref.get();
            if (l == null || l == listener) listeners.remove(ref);
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
        for (WeakReference<EegDataListener> ref : eegListeners) {
            EegDataListener l = ref.get();
            if (l == null || l == listener) eegListeners.remove(ref);
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
            this.lastRRIntervals = rrIntervals.clone();
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

        for (WeakReference<HeartRateListener> ref : listeners) {
            HeartRateListener listener = ref.get();
            if (listener == null) {
                listeners.remove(ref);
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
        for (WeakReference<EegDataListener> ref : eegListeners) {
            EegDataListener el = ref.get();
            if (el == null) {
                eegListeners.remove(ref);
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
