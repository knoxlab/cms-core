package com.powercms.cms.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SecurityConfigFrameOptionsTest.TestController.class)
@ContextConfiguration(classes = {
    SecurityConfig.class,
    SecurityConfigFrameOptionsTest.TestController.class,
    SecurityConfigFrameOptionsTest.I18nStubs.class
})
class SecurityConfigFrameOptionsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void previewPageDoesNotWriteXFrameOptions() throws Exception {
        mockMvc.perform(get("/api/v1/preview/page"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Frame-Options"));
    }

    @Test
    void ordinaryRequestStillWritesDenyXFrameOptions() throws Exception {
        mockMvc.perform(get("/ordinary").with(user("editor")))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    /**
     * 文档预览页内部用 {@code <iframe>} 嵌 {@code /media-preview} 让浏览器原生阅读器渲染。
     * {@code X-Frame-Options: DENY} 连**同源**嵌套也会拦，故该路径必须与 {@code /media-preview}
     * 同口径豁免 —— 否则 PDF 预览会静默变成一块空白（iframe 被浏览器拒绝渲染）。
     */
    @Test
    void mediaPreviewDoesNotWriteXFrameOptions() throws Exception {
        mockMvc.perform(get("/media-preview/site/asset"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Frame-Options"));
    }

    @TestConfiguration
    static class I18nStubs {
        @Bean
        com.powercms.cms.identity.UserLanguageService userLanguageService() {
            return org.mockito.Mockito.mock(com.powercms.cms.identity.UserLanguageService.class);
        }
    }

    @Controller
    static class TestController {
        @GetMapping("/api/v1/preview/page")
        @ResponseBody
        String previewPage() {
            return "preview";
        }

        @GetMapping("/ordinary")
        @ResponseBody
        String ordinary() {
            return "ordinary";
        }

        @GetMapping("/media-preview/{siteId}/{assetId}")
        @ResponseBody
        String mediaPreview() {
            return "preview";
        }
    }
}
