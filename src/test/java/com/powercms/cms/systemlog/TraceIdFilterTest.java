package com.powercms.cms.systemlog;

import com.powercms.cms.platform.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-LOG-TR-001/002/003、AC-LOG-019：TraceIdFilter 生成/透传/清理。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    private record Result(String mdcDuring, String responseHeader) {}

    private Result pass(MockHttpServletRequest request) throws Exception {
        String[] mdcDuring = new String[1];
        FilterChain chain = (req, res) -> mdcDuring[0] = MDC.get("traceId");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return new Result(mdcDuring[0], response.getHeader(TraceIdFilter.HEADER));
    }

    @Test
    void generatesUuidWithoutDashesWhenHeaderMissing() throws Exception {
        Result r = pass(new MockHttpServletRequest());
        assertThat(r.mdcDuring()).isNotBlank();
        assertThat(r.mdcDuring()).doesNotContain("-");
        assertThat(r.responseHeader()).isEqualTo(r.mdcDuring());
    }

    @Test
    void honorsIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "trace-from-gateway-001");
        Result r = pass(request);
        assertThat(r.mdcDuring()).isEqualTo("trace-from-gateway-001");
        assertThat(r.responseHeader()).isEqualTo("trace-from-gateway-001");
    }

    @Test
    void mdcClearedAfterRequest() throws Exception {
        // FR-LOG-TR-002：finally 中移除，防线程复用串号
        pass(new MockHttpServletRequest());
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void overlongHeaderRegenerated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "x".repeat(200));
        Result r = pass(request);
        assertThat(r.mdcDuring()).hasSize(32).doesNotContain("xxxxx");
    }
}
