package com.xinji.heartbeat.bluetooth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HeartRateJudger {

    private static final int HR_MIN = 25;
    private static final int HR_MAX = 250;
    private static final int MAX_JUMP = 30;

    private HeartRateJudger() {}

    public static DetectResult judge(List<byte[]> samples, String serviceUuid, String charUuid) {
        if (samples == null || samples.isEmpty()) {
            return new DetectResult(serviceUuid, charUuid, samples, -1, DetectResult.Confidence.LOW);
        }

        String uuidLower = charUuid.toLowerCase(Locale.ROOT);

        if (uuidLower.contains("2a37")) {
            int hr = tryParseStandardHr(samples.get(samples.size() - 1));
            return new DetectResult(serviceUuid, charUuid, samples,
                    hr > 0 ? hr : -1, DetectResult.Confidence.HIGH);
        }

        List<Integer> parsedValues = new ArrayList<>();
        for (byte[] raw : samples) {
            int val = tryParseAny(raw);
            if (val >= HR_MIN && val <= HR_MAX) {
                parsedValues.add(val);
            }
        }

        if (parsedValues.isEmpty()) {
            return new DetectResult(serviceUuid, charUuid, samples, -1, DetectResult.Confidence.LOW);
        }

        boolean hasRR = false;
        for (byte[] raw : samples) {
            if (raw.length >= 3 && (raw[0] & 0x10) != 0) {
                hasRR = true;
                break;
            }
        }
        if (hasRR) {
            int latest = parsedValues.get(parsedValues.size() - 1);
            return new DetectResult(serviceUuid, charUuid, samples, latest, DetectResult.Confidence.HIGH);
        }

        boolean smooth = true;
        for (int i = 1; i < parsedValues.size(); i++) {
            int diff = Math.abs(parsedValues.get(i) - parsedValues.get(i - 1));
            if (diff > MAX_JUMP) {
                smooth = false;
                break;
            }
        }

        int latest = parsedValues.get(parsedValues.size() - 1);
        DetectResult.Confidence confidence;

        if (parsedValues.size() >= 3 && smooth && latest >= 30 && latest <= 220) {
            confidence = DetectResult.Confidence.MEDIUM;
        } else {
            confidence = DetectResult.Confidence.LOW;
        }

        return new DetectResult(serviceUuid, charUuid, samples, latest, confidence);
    }

    private static int tryParseStandardHr(byte[] data) {
        if (data == null || data.length < 2) return -1;
        try {
            int flags = data[0] & 0xFF;
            int hr = (flags & 0x01) == 0
                    ? (data[1] & 0xFF)
                    : ((data[1] & 0xFF) | ((data[2] & 0xFF) << 8));
            if (hr > HR_MIN && hr < HR_MAX) return hr;
        } catch (Exception ignored) {}
        return -1;
    }

    private static int tryParseAny(byte[] data) {
        if (data == null || data.length == 0) return -1;
        if (data.length == 1) return data[0] & 0xFF;
        int hr = tryParseStandardHr(data);
        if (hr > 0) return hr;
        return data[1] & 0xFF;
    }
}
