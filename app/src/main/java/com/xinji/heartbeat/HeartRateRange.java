package com.xinji.heartbeat;

public final class HeartRateRange {

    public static final int MIN = 20;
    public static final int MAX = 250;
    public static final String ABNORMAL_LABEL = "数值异常";

    private HeartRateRange() {}

    public static boolean isValid(int hr) {
        return hr >= MIN && hr <= MAX;
    }
}