package com.powercms.cms.platform.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * DEF-11-11：SPA 壳请求判定——只有 GET 浏览器导航、非保留前缀、无扩展名的路径才回退 index.html。
 */
class SpaShellRequestMatcherTest {

    private final SpaShellRequestMatcher matcher = new SpaShellRequestMatcher();

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Accept", "text/html,application/xhtml+xml");
        return request;
    }

    @Test
    void servedForSingleAndMultiSegmentClientRoutes() {
        assertTrue(matcher.matches(get("/login")));
        assertTrue(matcher.matches(get("/content")));
        assertTrue(matcher.matches(get("/interaction/review")));
        assertTrue(matcher.matches(get("/analytics/overview")));
        assertTrue(matcher.matches(get("/pages/editor")));
        assertTrue(matcher.matches(get("/containers/blog/home")));
    }

    @Test
    void notServedForBackendPrefixes() {
        for (String uri : new String[] {
            "/api/v1/me", "/api/v1/sites/x/contents", "/graphql/sites", "/mvc/sites/x",
            "/oauth/authorize", "/actuator/health", "/ws/notifications", "/assets/logo",
            "/media-preview/abc", "/media-thumb/abc", "/v3/api-docs", "/swagger-ui/index.html",
        }) {
            assertFalse(matcher.matches(get(uri)), uri + " 属后端保留前缀，不应放行为 SPA 壳");
        }
    }

    @Test
    void notServedForStaticFilesOrNonNavigationRequests() {
        assertFalse(matcher.matches(get("/index.html")), "入口文件由资源映射处理");
        assertFalse(matcher.matches(get("/favicon.svg")));
        assertFalse(matcher.matches(get("/assets/index-abc123.js")), "带扩展名视为静态资源");

        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/login");
        post.addHeader("Accept", "text/html");
        assertFalse(matcher.matches(post), "仅 GET 导航回退 SPA 壳");

        MockHttpServletRequest json = new MockHttpServletRequest("GET", "/login");
        json.addHeader("Accept", "application/json");
        assertFalse(matcher.matches(json), "非 HTML 客户端（如 MCP/curl JSON）不应拿到 HTML");

        MockHttpServletRequest noAccept = new MockHttpServletRequest("GET", "/login");
        assertTrue(matcher.matches(noAccept), "Accept 缺失按浏览器导航处理");
    }
}
