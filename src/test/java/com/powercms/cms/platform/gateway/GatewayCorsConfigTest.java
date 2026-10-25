package com.powercms.cms.platform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayCorsConfigTest {

    @Test
    void allowsConfiguredOriginWithoutCredentials() throws Exception {
        CorsFilter filter = new GatewayCorsConfig().publicApiCorsFilter("https://app.example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/delivery/sites/x/contents");
        request.addHeader(HttpHeaders.ORIGIN, "https://app.example.com");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
            .isEqualTo("https://app.example.com");
        // setAllowCredentials(false) 时不回写 Allow-Credentials 头（默认即不允许）
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    @Test
    void wildcardModeEchoesRequestOrigin() throws Exception {
        CorsFilter filter = new GatewayCorsConfig().publicApiCorsFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/graphql");
        request.addHeader(HttpHeaders.ORIGIN, "https://random.site");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        // allowedOriginPattern("*") 会回显请求 Origin 而不是字面 "*"
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("https://random.site");
    }

    /**
     * FR-KNX-032：播放代码的三种形态都要能在**另一个域名**的页面里工作。
     * 这里守住 iframe（/media-play）、HLS（/media-stream）与 JS SDK 三条路径的 CORS 放行；
     * 少注册一条，表现为「代码看着对，换域名就播不了」。
     */
    @Test
    void mediaPlaybackPathsAllowCrossOrigin() throws Exception {
        CorsFilter filter = new GatewayCorsConfig().publicApiCorsFilter("");
        for (String path : new String[]{"/media-play/s/a", "/media-stream/s/a", "/media-player-sdk.js"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.addHeader(HttpHeaders.ORIGIN, "https://site.example.org");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .as("跨域播放路径 %s", path)
                .isEqualTo("https://site.example.org");
        }
    }

    /** HLS 分片播放器会带 Range 并在预检里声明；未列入允许头会被浏览器拦下。 */
    @Test
    void rangeRequestHeadersAreAllowedForMediaSegments() throws Exception {
        CorsFilter filter = new GatewayCorsConfig().publicApiCorsFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/media-stream/s/a");
        request.addHeader(HttpHeaders.ORIGIN, "https://site.example.org");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "range");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
            .containsIgnoringCase("range");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
            .containsIgnoringCase("Content-Range");
    }

    @Test
    void nonListedOriginGetsNoAllowHeaderWhenWhitelistConfigured() throws Exception {
        CorsFilter filter = new GatewayCorsConfig().publicApiCorsFilter("https://app.example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/sites/x/forms");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example.org");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String allowOrigin = response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowOrigin == null || "https://app.example.com".equals(allowOrigin)).isTrue();
    }
}
