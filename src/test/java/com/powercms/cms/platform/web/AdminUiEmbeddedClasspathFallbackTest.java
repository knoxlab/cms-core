package com.powercms.cms.platform.web;

import com.powercms.cms.platform.security.SecurityConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端前端内嵌（classpath 回退）契约：外部 admin-ui/dist 目录缺失时（典型：GraalVM
 * 原生二进制独立部署、工作目录下没有前端产物），资源处理器必须自动回退到内嵌的
 * {@code classpath:/admin-ui/dist/}，使二进制开箱即可完整服务管理端。
 *
 * <p>回退链见 {@link AdminUiWebConfig} 类注释：外部目录（覆盖）→ 内嵌 classpath。</p>
 *
 * <p>夹具：{@code src/test/resources/admin-ui/dist/}（index.html 含
 * {@code embedded-admin-ui-shell} 标记、{@code assets/embedded-fallback.js}）；
 * 本用例把系统属性 {@code powercms.admin-ui.dist} 指向<b>不存在</b>的目录——
 * {@link AdminUiWebConfig} 的显式覆盖契约是“不回退探测”，于是外部位置必然落空，
 * 请求只能由内嵌 classpath 兜底。与 {@link AdminUiStaticResourceTest}（外部目录优先）
 * 互为两翼，共同锁定优先级顺序。</p>
 */
@WebMvcTest(controllers = SpaForwardController.class)
@ContextConfiguration(classes = {
    SecurityConfig.class,
    AdminUiWebConfig.class,
    SpaForwardController.class,
    ApiExceptionHandler.class,
    AdminUiStaticResourceTest.I18nStubs.class
})
class AdminUiEmbeddedClasspathFallbackTest {

    private static final String EMBEDDED_SHELL_MARKER = "embedded-admin-ui-shell";
    private static final String EMBEDDED_ASSET_BODY = "console.log('embedded-fallback-asset');\n";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void adminUiDistMissing(DynamicPropertyRegistry registry) {
        Path missing = missingDistDir();
        // AdminUiWebConfig 在上下文刷新时解析该属性，此处必须先注入（契约同 AdminUiStaticResourceTest）。
        System.setProperty("powercms.admin-ui.dist", missing.toString());
        registry.add("powercms.admin-ui.test.dist", missing::toString);
    }

    @AfterAll
    static void clearOverride() {
        System.clearProperty("powercms.admin-ui.dist");
    }

    @Test
    void indexHtmlFallsBackToEmbeddedClasspath() throws Exception {
        mockMvc.perform(get("/index.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(EMBEDDED_SHELL_MARKER)))
            .andExpect(forwardedUrl(null));
    }

    @Test
    void rootForwardsToEmbeddedSpaShell() throws Exception {
        mockMvc.perform(get("/").header("Accept", "text/html"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void assetFallsBackToEmbeddedClasspath() throws Exception {
        mockMvc.perform(get("/assets/embedded-fallback.js"))
            .andExpect(status().isOk())
            .andExpect(content().string(EMBEDDED_ASSET_BODY));
    }

    @Test
    void clientDeepLinkStillForwardsToSpaShell() throws Exception {
        mockMvc.perform(get("/interaction/review").header("Accept", "text/html"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    /** 生成一个确定不存在的目录路径（覆盖语义下不回退探测，外部位置必然落空）。 */
    private static Path missingDistDir() {
        try {
            Path base = Files.createTempDirectory("powercms-admin-ui-missing-");
            return base.resolve("dist-not-created-" + UUID.randomUUID());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
