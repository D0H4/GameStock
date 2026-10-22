package com.gamestock.backend.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class TrustedClientAddressTest {
    private final TrustedClientAddress resolver=new TrustedClientAddress("127.0.0.1,::1");
    @Test void directPeerCannotSpoofForwardedIdentity() {
        var request=new MockHttpServletRequest();request.setRemoteAddr("198.51.100.20");request.addHeader("X-Forwarded-For","203.0.113.1");
        assertEquals("198.51.100.20",resolver.resolve(request));
    }
    @Test void trustedProxyKeepsDifferentClientsSeparateAndIgnoresInjectedLeftPrefix() {
        var a=new MockHttpServletRequest();a.setRemoteAddr("127.0.0.1");a.addHeader("X-Forwarded-For","1.2.3.4, 198.51.100.20");
        var b=new MockHttpServletRequest();b.setRemoteAddr("127.0.0.1");b.addHeader("X-Forwarded-For","198.51.100.21");
        assertEquals("198.51.100.20",resolver.resolve(a));assertEquals("198.51.100.21",resolver.resolve(b));
    }
    @Test void malformedForwardedAddressFallsBackWithoutDnsResolution() {
        var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");request.addHeader("X-Forwarded-For","attacker.example");
        assertEquals("127.0.0.1",resolver.resolve(request));
    }
}
