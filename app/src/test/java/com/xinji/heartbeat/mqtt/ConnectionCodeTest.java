package com.xinji.heartbeat.mqtt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ConnectionCodeTest {

    @Test
    public void parseV1PlainCode() {
        String[] r = MqttManager.parseConnectionCode(
                "HEARTBEAT#V1#ABC123#1.2.3.4:1883#heart/rate");
        assertNotNull(r);
        assertEquals(5, r.length);
        assertEquals("ABC123", r[0]);
        assertEquals("1.2.3.4", r[1]);
        assertEquals("1883", r[2]);
        assertEquals("heart/rate/ABC123", r[3]);
        assertEquals("0", r[4]);
    }

    @Test
    public void parseV2TlsCode() {
        String[] r = MqttManager.parseConnectionCode(
                "HEARTBEAT#V2#TAG9#broker.example.com:8883#heart/rate");
        assertNotNull(r);
        assertEquals("TAG9", r[0]);
        assertEquals("broker.example.com", r[1]);
        assertEquals("8883", r[2]);
        assertEquals("heart/rate/TAG9", r[3]);
        assertEquals("1", r[4]);
    }

    @Test
    public void parseCodeWithTopicAlreadyTagged() {
        String[] r = MqttManager.parseConnectionCode(
                "HEARTBEAT#V1#TAG#host:9090#heart/rate/TAG");
        assertNotNull(r);
        assertEquals("heart/rate/TAG", r[3]);
    }

    @Test
    public void parseCodeWithIpv6Host() {
        String[] r = MqttManager.parseConnectionCode(
                "HEARTBEAT#V2#TAG#[::1]:8883#heart/rate");
        assertNotNull(r);
        assertEquals("::1", r[1]);
        assertEquals("8883", r[2]);
        assertEquals("1", r[4]);
    }

    @Test
    public void parseCodeTrimsWhitespace() {
        String[] r = MqttManager.parseConnectionCode(
                "  HEARTBEAT#V1# TAG # host.local : 1883 # heart/rate  ");
        assertNotNull(r);
        assertEquals("TAG", r[0]);
        assertEquals("host.local", r[1]);
        assertEquals("1883", r[2]);
    }

    @Test
    public void rejectNullAndEmpty() {
        assertNull(MqttManager.parseConnectionCode(null));
        assertNull(MqttManager.parseConnectionCode(""));
        assertNull(MqttManager.parseConnectionCode("   "));
    }

    @Test
    public void rejectWrongPrefix() {
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT2#V1#T#h:1#t"));
        assertNull(MqttManager.parseConnectionCode("heartbeat#V1#T#h:1#t"));
    }

    @Test
    public void rejectUnknownVersion() {
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V3#T#h:1883#t"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#v1#T#h:1883#t"));
    }

    @Test
    public void rejectMissingSegments() {
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T#h:1883"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1"));
    }

    @Test
    public void rejectEmptyFields() {
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1##h:1883#t"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T##t"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T#h:1883#"));
    }

    @Test
    public void rejectBadHostPort() {
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T#h1883#t"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T#:1883#t"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T#h:abc#t"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T#h:0#t"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T#h:65536#t"));
        assertNull(MqttManager.parseConnectionCode("HEARTBEAT#V1#T#[::1]1883#t"));
    }

    @Test
    public void generatedCodeRoundTrips() {
        String code = MqttManager.generateConnectionCode("broker.example.com", 8883, "heart/rate", true);
        assertNotNull(code);
        String[] r = MqttManager.parseConnectionCode(code);
        assertNotNull(r);
        assertEquals("broker.example.com", r[1]);
        assertEquals("8883", r[2]);
        assertEquals("heart/rate", r[3].substring(0, "heart/rate".length()));
        assertEquals("1", r[4]);
    }

    @Test
    public void generatedPlainCodeIsV1() {
        String code = MqttManager.generateConnectionCode("1.2.3.4", 1883, "heart/rate", false);
        assertNotNull(code);
        String[] r = MqttManager.parseConnectionCode(code);
        assertNotNull(r);
        assertEquals("0", r[4]);
        assertArrayEquals("1883".getBytes(), r[2].getBytes());
    }
}