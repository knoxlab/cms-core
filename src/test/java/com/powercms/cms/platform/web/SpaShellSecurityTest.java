package com.powercms.cms.platform.web;

import com.powercms.cms.platform.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DEF-11-11 回归：未登录直达 SPA 客户端路由（登录页书签 / 会话过期后 F5 深链）必须拿到前端壳，
 * 而 API 仍返回 JSON 401，后端保留前缀下的未知路径保持 404（不得回退成 HTML）。
 */
@WebMvcTest(controllers = SpaForwardController.class)
@ContextConfiguration(classes = {
    SecurityConfig.class,
    SpaForwardController.class,
    ApiExceptionHandler.class,
    SpaShellSecurityTest.I18nStubs.class
})
class SpaShellSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginPageServedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/login").header("Accept", "text/html"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void deepLinkServedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/interaction/review").header("Accept", "text/html"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void apiStillRejectedWithJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/contents").header("Accept", "application/json"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathUnderReservedPrefixStays404() throws Exception {
        // 保留前缀下的未知路径：404 统一错误体（此前抛 ResponseStatusException 会被兜底处理器
        // 当成未处理异常返回 500，回归探针在真实栈上发现，故断言 code 而非仅状态码）
        mockMvc.perform(get("/api/v1/nonexistent").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user("admin")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(forwardedUrl(null));
    }

    @Test
    void unknownPathUnderMediaPrefixStays404WithoutHtml() throws Exception {
        // 媒体保留前缀下的未知路径：不得回退成 index.html（测试切片中由静态资源处理器直接 404）
        mockMvc.perform(get("/media-file/not-exists-probe").header("Accept", "text/html"))
            .andExpect(status().isNotFound())
            .andExpect(forwardedUrl(null));
    }

    @Test
    void writeToSpaRouteStillRequiresAuthentication() throws Exception {
        // 非 GET 不放行 SPA 壳；此处在 CSRF 过滤器即被拒（403），关键是不能拿到前端页面
        mockMvc.perform(post("/login"))
            .andExpect(status().is4xxClientError())
            .andExpect(forwardedUrl(null));
    }

    @TestConfiguration
    static class I18nStubs {
        @Bean
        com.powercms.cms.identity.UserLanguageService userLanguageService() {
            return org.mockito.Mockito.mock(com.powercms.cms.identity.UserLanguageService.class);
        }
    }
}
