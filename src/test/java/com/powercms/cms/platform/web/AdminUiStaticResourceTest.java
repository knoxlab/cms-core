package com.powercms.cms.platform.web;

import com.powercms.cms.platform.security.SecurityConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OBS-MSRC-03 回归：{@code /assets/**} 下的**嵌套目录**静态资源必须由资源处理器服务。
 *
 * <p>根因：SPA catch-all（{@link SpaForwardController}）的 {@code @GetMapping} 只约束了前两段不含
 * {@code .}，因此 {@code /assets/nested/chunk.js} 这类三段以上路径会被它抢先匹配
 * （{@code RequestMappingHandlerMapping} 优先级高于资源处理器映射），又因首段 {@code assets}
 * 属保留前缀而走 404 分支——磁盘上真实存在的文件被遮蔽成 404。修复方式：catch-all 的首段
 * 整体排除静态资源前缀（{@link AdminUiWebConfig#NOT_STATIC_PREFIX}），让请求落到资源处理器。</p>
 *
 * <p>用例口径：
 * <ul>
 *   <li>嵌套真实文件 → 200 且返回文件内容（修复前 404，本用例即回归闸门）；</li>
 *   <li>扁平真实文件 → 200（原有行为不回退）；</li>
 *   <li>嵌套缺失文件 / 保留前缀下的未知路径 → 404 统一错误体（{@code code=NOT_FOUND}），不回落 HTML；</li>
 *   <li>客户端深链 → 仍转发 index.html（catch-all 未被改坏）。</li>
 * </ul>
 *
 * <p>夹具：本用例用临时目录充当 admin-ui 构建产物，通过系统属性 {@code powercms.admin-ui.dist}
 * 覆盖路径（{@link AdminUiWebConfig} 的既有覆盖契约），因此不依赖仓库里是否已构建前端。
 * 临时目录**不删除**（进程内其他用例若复用该上下文仍可稳定读取）；系统属性在类结束后清掉，
 * 避免影响同 JVM 内后跑的用例。</p>
 */
@WebMvcTest(controllers = SpaForwardController.class)
@ContextConfiguration(classes = {
    SecurityConfig.class,
    AdminUiWebConfig.class,
    SpaForwardController.class,
    ApiExceptionHandler.class,
    AdminUiStaticResourceTest.I18nStubs.class
})
class AdminUiStaticResourceTest {

    private static final String NESTED_FILE = "/assets/nested/chunk-DEF03.js";
    private static final String NESTED_BODY = "console.log('nested-asset');\n";
    private static final String FLAT_FILE = "/assets/app-DEF03.js";
    private static final String FLAT_BODY = "console.log('flat-asset');\n";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void adminUiDist(DynamicPropertyRegistry registry) {
        Path dist = prepareDist();
        // AdminUiWebConfig 在类初始化时读系统属性/环境变量，故必须在上下文刷新前注入。
        System.setProperty("powercms.admin-ui.dist", dist.toString());
        registry.add("powercms.admin-ui.test.dist", dist::toString);
    }

    @AfterAll
    static void clearOverride() {
        System.clearProperty("powercms.admin-ui.dist");
    }

    @Test
    void nestedAssetIsServedInsteadOfShadowedByCatchAll() throws Exception {
        mockMvc.perform(get(NESTED_FILE))
            .andExpect(status().isOk())
            .andExpect(content().string(NESTED_BODY))
            .andExpect(forwardedUrl(null));
    }

    @Test
    void nestedAssetIsServedForBrowserLikeAcceptHeaderToo() throws Exception {
        // 浏览器导航语义（Accept: text/html）下也不得回落成 SPA 壳，必须是文件本身。
        mockMvc.perform(get(NESTED_FILE).header("Accept", "text/html"))
            .andExpect(status().isOk())
            .andExpect(content().string(NESTED_BODY))
            .andExpect(forwardedUrl(null));
    }

    @Test
    void flatAssetBehaviourIsUnchanged() throws Exception {
        mockMvc.perform(get(FLAT_FILE))
            .andExpect(status().isOk())
            .andExpect(content().string(FLAT_BODY));
    }

    @Test
    void missingNestedAssetKeepsUnified404Body() throws Exception {
        mockMvc.perform(get("/assets/nested/not-exists-DEF03.js"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(forwardedUrl(null));
    }

    @Test
    void missingFlatAssetKeepsUnified404Body() throws Exception {
        mockMvc.perform(get("/assets/not-exists-DEF03.js"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(forwardedUrl(null));
    }

    @Test
    void clientDeepLinkStillForwardsToSpaShell() throws Exception {
        mockMvc.perform(get("/interaction/review").header("Accept", "text/html"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void unknownPathUnderReservedPrefixStillUnified404() throws Exception {
        // 保留前缀（非静态资源）下的未知路径语义不变：统一 404，不得因排除静态前缀而变成 HTML。
        mockMvc.perform(get("/api/v1/definitely-missing").with(user("admin")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(forwardedUrl(null));
    }

    @Test
    void unknownPathUnderMediaPrefixStays404WithoutHtml() throws Exception {
        mockMvc.perform(get("/media-file/not-exists-def03").header("Accept", "text/html"))
            .andExpect(status().isNotFound())
            .andExpect(forwardedUrl(null));
    }

    private static Path prepareDist() {
        try {
            Path dist = Files.createTempDirectory("powercms-admin-ui-dist-");
            Files.writeString(dist.resolve("index.html"),
                "<!doctype html><html><body>spa-shell</body></html>", StandardCharsets.UTF_8);
            Path assets = Files.createDirectories(dist.resolve(AdminUiWebConfig.STATIC_PREFIX));
            Files.writeString(assets.resolve("app-DEF03.js"), FLAT_BODY, StandardCharsets.UTF_8);
            Path nested = Files.createDirectories(assets.resolve("nested"));
            Files.writeString(nested.resolve("chunk-DEF03.js"), NESTED_BODY, StandardCharsets.UTF_8);
            return dist;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration
    static class I18nStubs {
        @Bean
        com.powercms.cms.identity.UserLanguageService userLanguageService() {
            return org.mockito.Mockito.mock(com.powercms.cms.identity.UserLanguageService.class);
        }
    }
}
