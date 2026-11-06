package com.english9x.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

    private static AuthProperties props(List<String> proxies) {
        return new AuthProperties("0123456789abcdef0123456789abcdef", "english9x", Duration.ofMinutes(15), Duration.ofDays(30),
                Duration.ofSeconds(10), "10/15m", "3/1h", "5/1h", "20/1h", Duration.ofHours(24), Duration.ofHours(1),
                Duration.ofSeconds(60), 4, 8, proxies, "x <x@x.test>", null);
    }

    private static MockHttpServletRequest request(String peer, String... forwardedFor) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(peer);
        for (String f : forwardedFor) {
            r.addHeader("X-Forwarded-For", f);
        }
        return r;
    }

    @Test
    void withoutTrustedProxiesTheHeaderIsIgnored() {
        ClientIp ip = new ClientIp(props(List.of()));
        assertEquals("172.18.0.5", ip.of(request("172.18.0.5", "1.2.3.4")));
    }

    @Test
    void anUntrustedPeerCannotUseTheHeaderEvenIfProxiesAreConfigured() {
        ClientIp ip = new ClientIp(props(List.of("172.16.0.0/12")));
        assertEquals("198.51.100.1", ip.of(request("198.51.100.1", "1.2.3.4")));
    }

    @Test
    void aTrustedProxyVouchesForTheRightmostUntrustedAddress() {
        ClientIp ip = new ClientIp(props(List.of("172.16.0.0/12", "10.0.0.0/8")));
        assertEquals("203.0.113.9", ip.of(request("172.18.0.5", "203.0.113.9")));
        assertEquals("203.0.113.9", ip.of(request("172.18.0.5", "6.6.6.6, 203.0.113.9")), "forged left entries are ignored");
        assertEquals("203.0.113.9", ip.of(request("172.18.0.5", "6.6.6.6, 203.0.113.9, 10.1.1.1")), "inner proxies are skipped");
        assertEquals("203.0.113.9", ip.of(request("172.18.0.5", "6.6.6.6", "203.0.113.9")), "several header lines");
        assertEquals("172.18.0.5", ip.of(request("172.18.0.5")), "no header: the peer");
        assertEquals("172.18.0.5", ip.of(request("172.18.0.5", "10.1.1.1")), "only proxies listed: the peer");
    }

    @Test
    void garbageInTheHeaderIsJustAnotherString() {
        ClientIp ip = new ClientIp(props(List.of("172.16.0.0/12")));
        assertEquals("not-an-ip", ip.of(request("172.18.0.5", "not-an-ip")), "never resolved as a host name");
    }

    @Test
    void ipv6AndMasksWork() {
        ClientIp ip = new ClientIp(props(List.of("::1/128", "192.168.1.128/25")));
        assertEquals("203.0.113.9", ip.of(request("::1", "203.0.113.9")));
        assertEquals("203.0.113.9", ip.of(request("192.168.1.200", "203.0.113.9")));
        assertEquals("192.168.1.100", ip.of(request("192.168.1.100", "203.0.113.9")), "outside the /25");
    }

    @Test
    void aBadProxyEntryFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ClientIp(props(List.of("not a cidr"))));
        assertThrows(IllegalArgumentException.class, () -> new ClientIp(props(List.of("10.0.0.0/40"))));
    }
}
