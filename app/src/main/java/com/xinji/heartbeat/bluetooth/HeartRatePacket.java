package com.xinji.heartbeat.bluetooth;

public final class HeartRatePacket {

    public static final int FLAG_HR_16BIT = 0x01;
    public static final int FLAG_SENSOR_CONTACT = 0x02;
    public static final int FLAG_ENERGY_EXPENDED = 0x08;
    public static final int FLAG_RR_PRESENT = 0x10;

    private final int heartRate;
    private final int energyExpended;
    private final boolean hasEnergyExpended;
    private final int[] rrIntervals;

    private HeartRatePacket(int heartRate, int energyExpended, boolean hasEnergyExpended, int[] rrIntervals) {
        this.heartRate = heartRate;
        this.energyExpended = energyExpended;
        this.hasEnergyExpended = hasEnergyExpended;
        this.rrIntervals = rrIntervals;
    }

    public int getHeartRate() {
        return heartRate;
    }

    public int getEnergyExpended() {
        return energyExpended;
    }

    public boolean hasEnergyExpended() {
        return hasEnergyExpended;
    }

    public int[] getRrIntervals() {
        return rrIntervals;
    }

    public boolean hasRrIntervals() {
        return rrIntervals != null && rrIntervals.length > 0;
    }

    public static HeartRatePacket parse(byte[] data) {
        if (data == null || data.length < 2) {
            return null;
        }

        int flags = data[0] & 0xFF;
        boolean hr16 = (flags & FLAG_HR_16BIT) != 0;

        int hrBytes = hr16 ? 2 : 1;
        if (data.length < 1 + hrBytes) {
            return null;
        }

        int heartRate = hr16
                ? ((data[1] & 0xFF) | ((data[2] & 0xFF) << 8))
                : (data[1] & 0xFF);

        int offset = 1 + hrBytes;

        int energy = 0;
        boolean hasEnergy = false;
        if ((flags & FLAG_ENERGY_EXPENDED) != 0) {
            if (data.length < offset + 2) {
                return new HeartRatePacket(heartRate, 0, false, null);
            }
            energy = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            hasEnergy = true;
            offset += 2;
        }

        int[] rr = null;
        if ((flags & FLAG_RR_PRESENT) != 0 && data.length >= offset + 2) {
            int count = (data.length - offset) / 2;
            if (count > 0) {
                rr = new int[count];
                for (int i = 0; i < count; i++) {
                    int off = offset + i * 2;
                    rr[i] = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
                }
            }
        }

        return new HeartRatePacket(heartRate, energy, hasEnergy, rr);
    }

    public static int[] parseRrIntervals(byte[] data) {
        HeartRatePacket p = parse(data);
        return p == null ? null : p.getRrIntervals();
    }

}