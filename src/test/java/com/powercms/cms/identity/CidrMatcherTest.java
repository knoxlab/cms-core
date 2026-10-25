package com.powercms.cms.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CidrMatcherTest {

    @Test
    void blankAllowedFromAcceptsAnyIp() {
        assertThat(CidrMatcher.isAllowed(null, "10.0.0.8")).isTrue();
        assertThat(CidrMatcher.isAllowed("  ", "10.0.0.8")).isTrue();
    }

    @Test
    void exactAndCidrMatch() {
        assertThat(CidrMatcher.isAllowed("127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(CidrMatcher.isAllowed("10.0.0.0/8", "10.1.2.3")).isTrue();
        assertThat(CidrMatcher.isAllowed("10.0.0.0/8", "11.0.0.1")).isFalse();
        assertThat(CidrMatcher.isAllowed("192.168.1.0/24,10.0.0.1", "192.168.1.20")).isTrue();
    }

    @Test
    void ipv6LoopbackMapsToLocalhost() {
        assertThat(CidrMatcher.isAllowed("127.0.0.1", "::1")).isTrue();
    }
}
