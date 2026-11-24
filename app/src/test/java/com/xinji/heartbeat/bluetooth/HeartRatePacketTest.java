package com.xinji.heartbeat.bluetooth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HeartRatePacketTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    public void eightBit_noEnergy_noRr() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x00, 72));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertFalse(p.hasEnergyExpended());
        assertFalse(p.hasRrIntervals());
    }

    @Test
    public void eightBit_noEnergy_withRr() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x10, 72, 0x00, 0x04, 0x10, 0x00));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertFalse(p.hasEnergyExpended());
        assertArrayEquals(new int[]{1024, 16}, p.getRrIntervals());
    }

    @Test
    public void eightBit_withEnergy_noRr() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x08, 72, 0x64, 0x00));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertTrue(p.hasEnergyExpended());
        assertEquals(100, p.getEnergyExpended());
        assertFalse(p.hasRrIntervals());
    }

    @Test
    public void eightBit_withEnergy_withRr() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x18, 72, 0x64, 0x00, 0x00, 0x04, 0x10, 0x00));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertTrue(p.hasEnergyExpended());
        assertEquals(100, p.getEnergyExpended());
        assertArrayEquals(new int[]{1024, 16}, p.getRrIntervals());
    }

    @Test
    public void sixteenBit_noEnergy_noRr() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x01, 0x48, 0x00));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertFalse(p.hasEnergyExpended());
        assertFalse(p.hasRrIntervals());
    }

    @Test
    public void sixteenBit_noEnergy_withRr() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x11, 0x48, 0x00, 0x00, 0x04, 0x10, 0x00));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertFalse(p.hasEnergyExpended());
        assertArrayEquals(new int[]{1024, 16}, p.getRrIntervals());
    }

    @Test
    public void sixteenBit_withEnergy_noRr() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x09, 0x48, 0x00, 0x64, 0x00));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertTrue(p.hasEnergyExpended());
        assertEquals(100, p.getEnergyExpended());
        assertFalse(p.hasRrIntervals());
    }

    @Test
    public void sixteenBit_withEnergy_withRr() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x19, 0x48, 0x00, 0x64, 0x00, 0x00, 0x04, 0x10, 0x00));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertTrue(p.hasEnergyExpended());
        assertEquals(100, p.getEnergyExpended());
        assertArrayEquals(new int[]{1024, 16}, p.getRrIntervals());
    }

    @Test
    public void rejectsShortPacket() {
        assertNull(HeartRatePacket.parse(null));
        assertNull(HeartRatePacket.parse(bytes()));
        assertNull(HeartRatePacket.parse(bytes(0x01)));
        assertNull(HeartRatePacket.parse(bytes(0x01, 0x48)));
    }

    @Test
    public void energyFlagWithoutEnergyBytes() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x08, 72));
        assertNotNull(p);
        assertEquals(72, p.getHeartRate());
        assertFalse(p.hasEnergyExpended());
        assertFalse(p.hasRrIntervals());
    }

    @Test
    public void rrNotReadWhenFlagAbsent() {
        HeartRatePacket p = HeartRatePacket.parse(bytes(0x00, 72, 0x00, 0x04));
        assertNotNull(p);
        assertFalse(p.hasRrIntervals());
    }
}