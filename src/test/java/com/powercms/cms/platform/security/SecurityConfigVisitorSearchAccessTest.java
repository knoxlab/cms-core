package com.powercms.cms.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-SEARCH-004：访客检索（能力清单 / 搜索建议）必须对匿名访客开放。
 *
 * <p>这里以真实安全过滤链（{@code @WebMvcTest} + {@link SecurityConfig}）断言放行面与写面的边界：
 * 读面开放、写面（索引重建）仍然收敛。
 */
@WebMvcTest(SecurityConfigVisitorSearchAccessTest.StubController.class)
@ContextConfiguration(classes = {
    SecurityConfig.class,
    SecurityConfigVisitorSearchAccessTest.StubController.class,
    SecurityConfigVisitorSearchAccessTest.I18nStubs.class
})
class SecurityConfigVisitorSearchAccessTest {

    private static final String SITE = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private MockMvc mockMvc;

    /** 访客端能力清单：匿名可用（前台据此渲染筛选面板）。 */
    @Test
    void anonymousVisitorCapabilitiesIsOpen() throws Exception {
        mockMvc.perform(get("/api/v1/public/sites/" + SITE + "/search/capabilities"))
            .andExpect(status().isOk());
    }

    /** 访客端搜索建议：匿名可用。 */
    @Test
    void anonymousVisitorSuggestIsOpen() throws Exception {
        mockMvc.perform(get("/api/v1/public/sites/" + SITE + "/search/suggest")
                .param("field", "title").param("q", "a"))
            .andExpect(status().isOk());
    }

    /** 放行读面不得连带放行写面：访客索引重建仍要求 system-admin（匿名 → 401）。 */
    @Test
    void anonymousPublicRebuildIsStillRejected() throws Exception {
        mockMvc.perform(post("/api/v1/sites/" + SITE + "/search/public/rebuild"))
            .andExpect(status().isUnauthorized());
    }

    /** 同一条写面：已登录但非 system-admin → 403（不是 404，说明规则先于路由生效）。 */
    @Test
    void nonSystemAdminPublicRebuildIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/sites/" + SITE + "/search/public/rebuild").with(user("editor")))
            .andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class I18nStubs {
        @Bean
        com.powercms.cms.identity.UserLanguageService userLanguageService() {
            return org.mockito.Mockito.mock(com.powercms.cms.identity.UserLanguageService.class);
        }
    }

    @Controller
    static class StubController {
        // 用真实端点形态的占位控制器：路径要能命中 SecurityConfig 里的规则
        @GetMapping("/api/v1/public/sites/{siteId}/search/capabilities")
        @ResponseBody
        String visitorCapabilities() {
            return "{}";
        }

        @GetMapping("/api/v1/public/sites/{siteId}/search/suggest")
        @ResponseBody
        String visitorSuggest() {
            return "{}";
        }
    }
}
