package main

import "testing"

func TestParseCodeValid(t *testing.T) {
	cases := []struct {
		code      string
		wantAddr  string
		wantTopic string
		wantTLS   bool
	}{
		{"HEARTBEAT#V1#ABC123#1.2.3.4:1883#heart/rate", "1.2.3.4:1883", "heart/rate/ABC123", false},
		{"HEARTBEAT#V2#ABC123#1.2.3.4:8883#heart/rate", "1.2.3.4:8883", "heart/rate/ABC123", true},
		{"HEARTBEAT#V1#TAG#host.local:1234#a/b/", "host.local:1234", "a/b//TAG", false},
		{"HEARTBEAT#V1#TAG#1.2.3.4:1883#heart/rate/TAG", "1.2.3.4:1883", "heart/rate/TAG", false},
		{"HEARTBEAT#V2#TAG#[::1]:8883#heart/rate", "[::1]:8883", "heart/rate/TAG", true},
		{"  HEARTBEAT#V1#TAG#1.2.3.4:1883#heart/rate  ", "", "", false},
	}
	for _, c := range cases {
		addr, topic, tls := parseCode(c.code)
		if c.code[:2] == "  " {
			if addr != "" || topic != "" {
				t.Errorf("expected reject for %q, got %q %q", c.code, addr, topic)
			}
			continue
		}
		if addr != c.wantAddr || topic != c.wantTopic || tls != c.wantTLS {
			t.Errorf("parseCode(%q) = (%q, %q, %v), want (%q, %q, %v)",
				c.code, addr, topic, tls, c.wantAddr, c.wantTopic, c.wantTLS)
		}
	}
}

func TestParseCodeInvalid(t *testing.T) {
	bad := []string{
		"",
		"heartbeat#V1#TAG#1.2.3.4:1883#t",
		"HEARTBEAT#V3#TAG#1.2.3.4:1883#t",
		"HEARTBEAT#V1#TAG#1.2.3.4:1883",
		"HEARTBEAT#V1##1.2.3.4:1883#t",
		"HEARTBEAT#V1#TAG##t",
		"HEARTBEAT#V1#TAG#1.2.3.4:abc#t",
		"HEARTBEAT#V1#TAG#1.2.3.4:0#t",
		"HEARTBEAT#V1#TAG#1.2.3.4:65536#t",
		"HEARTBEAT#V1#TAG#1.2.3.4#t",
		"HEARTBEAT#V1#TAG#:1883#t",
		"HEARTBEAT#V1#TAG#[::1]1883#t",
	}
	for _, c := range bad {
		addr, topic, _ := parseCode(c)
		if addr != "" || topic != "" {
			t.Errorf("parseCode(%q) should be rejected, got (%q, %q)", c, addr, topic)
		}
	}
}