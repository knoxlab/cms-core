package com.powercms.cms.systemlog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-LOG-AC-002（XFF 还原，最大 8 跳）、FR-LOG-AC-002（结果分类）单测。
 */
class AccessLogFilterTest {

    private final SystemLogProperties props = new SystemLogProperties();

    private static HttpServletRequest request(String uri, String xff, String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (xff != null) req.addHeader("X-Forwarded-For", xff);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    void xffSingleHopUsesThatHop() {
        var filter = new AccessLogFilter(new SystemLogPipeline(props, null, null), props);
        String ip = AccessLogFilter.resolveClientIp(request("/api/v1/meta", "203.0.113.9", "10.0.0.1"));
        assertThat(ip).isEqualTo("203.0.113.9");
    }

    @Test
    void xffCappedAtEightHops() {
        String xff = "203.0.113.1, 203.0.113.2, 203.0.113.3, 203.0.113.4, 203.0.113.5, 203.0.113.6, 203.0.113.7, 203.0.113.8, 203.0.113.9";
        String ip = AccessLogFilter.resolveClientIp(request("/api/v1/meta", xff, "10.0.0.1"));
        // 9 跳只回看 8 跳：最右第 8 跳 = 203.0.113.2
        assertThat(ip).isEqualTo("203.0.113.2");
    }

    @Test
    void noXffFallsBackToRemoteAddr() {
        String ip = AccessLogFilter.resolveClientIp(request("/api/v1/meta", null, "192.168.1.5"));
        assertThat(ip).isEqualTo("192.168.1.5");
    }

    @Test
    void outcomeClassification() {
        assertThat(AccessLogFilter.outcomeOf(200, null)).isEqualTo("SUCCESS");
        assertThat(AccessLogFilter.outcomeOf(302, null)).isEqualTo("SUCCESS");
        assertThat(AccessLogFilter.outcomeOf(400, null)).isEqualTo("BUSINESS_FAILURE");
        assertThat(AccessLogFilter.outcomeOf(401, null)).isEqualTo("AUTH_FAILURE");
        assertThat(AccessLogFilter.outcomeOf(403, null)).isEqualTo("AUTH_FAILURE");
        assertThat(AccessLogFilter.outcomeOf(429, null)).isEqualTo("RATE_LIMITED");
        assertThat(AccessLogFilter.outcomeOf(500, null)).isEqualTo("SYSTEM_ERROR");
        assertThat(AccessLogFilter.outcomeOf(200, new RuntimeException("x"))).isEqualTo("SYSTEM_ERROR");
    }

    @Test
    void excludedPathsSkipFilter() throws jakarta.servlet.ServletException {
        var filter = new AccessLogFilter(new SystemLogPipeline(props, null, null), props);
        assertThat(filter.shouldNotFilter(request("/actuator/health", null, "127.0.0.1"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/assets/index.js", null, "127.0.0.1"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/favicon.ico", null, "127.0.0.1"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/meta", null, "127.0.0.1"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/delivery/sites/x/pages", null, "127.0.0.1"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/auth/backend/login", null, "127.0.0.1"))).isFalse();
    }
}
