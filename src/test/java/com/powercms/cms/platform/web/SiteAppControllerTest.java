package com.powercms.cms.platform.web;

import com.powercms.cms.delivery.AnalyticsInjector;
import com.powercms.cms.model.AnalyticsComponentSeedService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitepackage.SitePackageProperties;
import com.powercms.cms.sitepackage.WebappsProperties;
import com.powercms.cms.sitepackage.WebappsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站点应用访问（/webapps/{siteKey}/**）：静态服务、SPA 回退、API 路径自动匹配。
 */
class SiteAppControllerTest {

    private static final String SITE_KEY = "demo";
    private static final UUID SITE_ID = UUID.randomUUID();

    @TempDir
    Path tmp;

    private MockMvc mvc;
    private ContentTypeRepository typeRepo;

    @BeforeEach
    void setUp() throws Exception {
        WebappsProperties props = new WebappsProperties();
        props.setRoot(tmp.toString());
        props.setBuild(WebappsProperties.BuildMode.NEVER);
        WebappsService webapps = new WebappsService(props, new SitePackageProperties());

        SiteEntity site = mock(SiteEntity.class);
        when(site.getId()).thenReturn(SITE_ID);
        org.mockito.Mockito.lenient().when(site.getSiteKey()).thenReturn(SITE_KEY);
        SiteRepository siteRepo = mock(SiteRepository.class);
        when(siteRepo.findBySiteKey(SITE_KEY)).thenReturn(Optional.of(site));
        org.mockito.Mockito.lenient().when(siteRepo.findById(SITE_ID)).thenReturn(Optional.of(site));

        // 统计采集组件类型存在（站点默认种子），站点应用应注入采集脚本
        typeRepo = mock(ContentTypeRepository.class);
        ContentTypeEntity trackerType = new ContentTypeEntity();
        trackerType.setId(UUID.randomUUID());
        when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(SITE_ID,
            AnalyticsComponentSeedService.ANALYTICS_TRACKER)).thenReturn(List.of(trackerType));

        // 部署一个最小站点应用
        Path dir = webapps.siteDir(SITE_KEY);
        Files.createDirectories(dir.resolve("assets"));
        Files.writeString(dir.resolve("index.html"),
            "<html><head><title>demo</title></head><body>app</body></html>");
        Files.writeString(dir.resolve("assets/app-abc.js"), "console.log(1);");

        SiteAppController controller = new SiteAppController(webapps, siteRepo,
            new AnalyticsInjector(typeRepo, siteRepo));
        mvc = MockMvcBuilders.standaloneSetup(controller, new PingController())
            .addFilters(new WebappsApiRewriteFilter())
            .build();
    }

    @Test
    void servesIndexWithInjectedBootConfigAndAnalyticsTracker() throws Exception {
        String body = mvc.perform(get("/webapps/" + SITE_KEY + "/"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains("window.__POWERCMS_SITE_ID__=\"" + SITE_ID + "\"")
            .contains("window.__POWERCMS_SITE_KEY__=\"" + SITE_KEY + "\"")
            .contains("window.__POWERCMS_API_BASE__=\"/api/v1\"")
            // 统计采集：配置里的 siteKey 是站点键（采集端按它解析站点），脚本排在 </head> 前、先于应用模块执行
            .contains("window.powercmsAnalyticsConfig={siteKey:\"" + SITE_KEY + "\"")
            .contains("/api/v1/delivery/analytics/script.js")
            // 注入点在 </head> 之前，保证应用模块脚本执行前配置就绪
            .containsPattern("(?s)window\\.__POWERCMS_SITE_ID__.*window\\.powercmsAnalyticsConfig.*</head>");
    }

    @Test
    void indexHasNoAnalyticsTrackerWhenComponentTypeDeleted() throws Exception {
        when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(SITE_ID,
            AnalyticsComponentSeedService.ANALYTICS_TRACKER)).thenReturn(List.of());
        String body = mvc.perform(get("/webapps/" + SITE_KEY + "/"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("__POWERCMS_SITE_ID__").doesNotContain("powercmsAnalyticsConfig");
    }

    /** 目录入口补尾斜杠：相对资源路径（Vite base './'）需以 /webapps/{siteKey}/ 为文档基解析。 */
    @Test
    void entryWithoutTrailingSlashRedirectsToSlash() throws Exception {
        mvc.perform(get("/webapps/" + SITE_KEY))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/webapps/" + SITE_KEY + "/"));
    }

    /** 重定向保留查询参数（应用地址带 ?siteId=… 入口）。 */
    @Test
    void entryRedirectPreservesQueryString() throws Exception {
        mvc.perform(get("/webapps/" + SITE_KEY + "?siteId=abc"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/webapps/" + SITE_KEY + "/?siteId=abc"));
    }

    @Test
    void servesStaticAssetWithLongCache() throws Exception {
        mvc.perform(get("/webapps/" + SITE_KEY + "/assets/app-abc.js"))
            .andExpect(status().isOk())
            .andExpect(content().string("console.log(1);"));
    }

    @Test
    void clientRoutesFallBackToIndexHtml() throws Exception {
        String body = mvc.perform(get("/webapps/" + SITE_KEY + "/library/some-id"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("__POWERCMS_SITE_ID__");
    }

    @Test
    void missingAssetWithExtensionIs404() throws Exception {
        mvc.perform(get("/webapps/" + SITE_KEY + "/assets/missing-xyz.js"))
            .andExpect(status().isNotFound());
    }

    @Test
    void unknownSiteKeyIs404() throws Exception {
        mvc.perform(get("/webapps/no-such-site")).andExpect(status().isNotFound());
    }

    @Test
    void traversalAttemptsAre404() throws Exception {
        mvc.perform(get("/webapps/" + SITE_KEY + "/..%2F..%2Fsecret.txt"))
            .andExpect(status().isNotFound());
    }

    /** API 路径自动匹配：应用基路径相对寻址透明改写到 /api/**。 */
    @Test
    void siteAppApiPathIsRewrittenToApi() throws Exception {
        mvc.perform(get("/webapps/" + SITE_KEY + "/api/v1/ping"))
            .andExpect(status().isOk()).andExpect(content().string("pong"));
    }

    @RestController
    static class PingController {
        @GetMapping("/api/v1/ping")
        String ping() { return "pong"; }
    }
}
