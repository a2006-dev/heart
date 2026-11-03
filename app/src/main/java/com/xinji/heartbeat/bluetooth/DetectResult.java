package com.xinji.heartbeat.bluetooth;

import java.util.List;

public class DetectResult {
    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public final String serviceUuid;
    public final String charUuid;
    public final List<byte[]> rawSamples;
    public final int candidateHr;
    public final Confidence confidence;

    public DetectResult(String serviceUuid, String charUuid,
                        List<byte[]> rawSamples, int candidateHr,
                        Confidence confidence) {
        this.serviceUuid = serviceUuid;
        this.charUuid = charUuid;
        this.rawSamples = rawSamples;
        this.candidateHr = candidateHr;
        this.confidence = confidence;
    }
}
