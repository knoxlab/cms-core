package com.powercms.cms.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.navigation.NavBreadcrumbBuilder;
import com.powercms.cms.delivery.navigation.NavTreeBuilder;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
import com.powercms.cms.model.AnalyticsComponentSeedService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.ComponentInstance;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.ThemePackageService;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerStructureRepository;
import com.powercms.cms.sitepackage.repo.SiteDropZoneRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 统计采集注入（FR-STAT-001 注入开关）：
 * 发布页命中 system.analytics-tracker 组件 → 恰好注入一次；
 * 预览/编辑模式不注入；无组件页面不注入。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageRenderAnalyticsInjectionTest {

    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository typeRepo;
    @Mock TemplateService templateService;
    @Mock PageCompositionService compositionService;
    @Mock FreeMarkerSandbox freeMarkerSandbox;
    @Mock PageVisualCompositionRepository compositionRepo;
    @Mock SiteDropZoneRepository zoneRepo;
    @Mock SiteContainerRepository containerRepo;
    @Mock SiteContainerStructureRepository containerStructureRepo;
    @Mock SiteTemplateRepository visualTemplateRepo;
    @Mock ThemePackageService themePackageService;
    @Mock ComponentRenderService componentRenderer;
    @Mock SiteRepository sites;
    @Mock CategoryRepository categories;
    @Mock NavTreeBuilder nav;
    @Mock NavBreadcrumbBuilder crumbs;
    @Mock UrlRewriteService urls;
    @Mock com.powercms.cms.identity.permission.ObjectPermissionService objectPermissions;

    private PageRenderService service;
    private ContentItemEntity page;
    private final UUID siteId = UUID.randomUUID();
    private final UUID pageId = UUID.randomUUID();
    private final UUID trackerTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RenderDatasetCache cache = new RenderDatasetCache(16);
        RenderContextFactory contextFactory = new RenderContextFactory(
            sites, categories, itemRepo, versionRepo, typeRepo, nav, crumbs, urls, "test");
        ContainerTemplateModelFactory containerModelFactory = org.mockito.Mockito.mock(ContainerTemplateModelFactory.class);
        service = new PageRenderService(itemRepo, versionRepo, typeRepo, templateService,
            new ObjectMapper(), compositionService, freeMarkerSandbox, compositionRepo,
            zoneRepo, containerRepo, containerStructureRepo, cache, visualTemplateRepo,
            themePackageService, componentRenderer, contextFactory, containerModelFactory,
            new AnalyticsInjector(typeRepo, analyticsSiteRepo()),
            objectPermissions,
            new com.powercms.cms.identity.permission.ViewerAccessService(objectPermissions, org.mockito.Mockito.mock(com.powercms.cms.identity.repo.UserRepository.class)),
            "https://example.com");

        page = new ContentItemEntity();
        page.setId(pageId);
        page.setSiteId(siteId);
        page.setRoutePath("/news");
        page.setTitle("新闻");
        page.setStatus("PUBLISHED");
        page.setTemplatePath("/site/page.ftl");
        page.setContentTypeId(UUID.randomUUID());
        page.setCurrentVersion(1);
        page.setPublishedVersion(1);
        lenient().when(itemRepo.findBySiteIdAndRoutePathAndStatus(siteId, "/news", "PUBLISHED"))
            .thenReturn(Optional.of(page));
        lenient().when(itemRepo.findBySiteId(siteId))
            .thenReturn(List.of(page));

        ContentVersionEntity version = ContentVersionEntity.create(pageId, 1, "{}", 1, "tester", null);
        lenient().when(versionRepo.findByContentItemIdAndVersion(pageId, 1)).thenReturn(Optional.of(version));

        ContentTypeEntity pageType = new ContentTypeEntity();
        pageType.setObjectType("PAGE");
        lenient().when(typeRepo.findById(page.getContentTypeId())).thenReturn(Optional.of(pageType));

        ContentTypeEntity trackerType = new ContentTypeEntity();
        trackerType.setId(trackerTypeId);
        trackerType.setObjectType("COMPONENT");
        trackerType.setSiteId(siteId);
        lenient().when(typeRepo.findById(trackerTypeId)).thenReturn(Optional.of(trackerType));
        lenient().when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId,
                AnalyticsComponentSeedService.ANALYTICS_TRACKER)).thenReturn(List.of(trackerType));

        lenient().when(freeMarkerSandbox.render(anyString(), anyString(), any(), anyBoolean()))
            .thenReturn("<html><body><p>hello</p></body></html>");
        lenient().when(compositionRepo.findByPageId(pageId)).thenReturn(Optional.empty());
        TemplateService.ProjInfo project = new TemplateService.ProjInfo(UUID.randomUUID(), siteId,
            "main", "FTL", null, 1, Instant.now());
        lenient().when(templateService.listProjects(siteId)).thenReturn(List.of(project));
        lenient().when(templateService.readFileContent(project.id(), "site/page.ftl"))
            .thenReturn("<h1>${contentModel.title!''}</h1>");
        lenient().when(sites.findById(siteId)).thenReturn(Optional.of(site()));
        // 默认匿名权限放行（未打破继承）
        lenient().when(objectPermissions.hasAnonymousRight(
            com.powercms.cms.identity.permission.ObjectType.CONTENT,
            pageId,
            com.powercms.cms.identity.permission.ObjectType.VIEW)).thenReturn(true);
    }

    private SiteEntity site() {
        SiteEntity site = new SiteEntity();
        site.setId(siteId);
        site.setSiteKey("demo");
        site.setName("演示站");
        site.setDefaultLocale("zh-CN");
        site.setTimezone("Asia/Shanghai");
        site.setStatus("ACTIVE");
        return site;
    }

    private ComponentInstance trackerComponent() {
        return new ComponentInstance(UUID.randomUUID().toString(), ComponentInstance.Kind.EMBEDDED,
            null, null, trackerTypeId, "统计采集", Map.of(), null, null, Map.of(), 0,
            null, Map.of(), null, null);
    }

    @Test
    void publishedPageInjectsScriptExactlyOnce() {
        when(compositionService.zones(pageId)).thenReturn(Map.of("footer", List.of(trackerComponent())));

        var rendered = service.renderPublished(siteId, "/news");

        assertThat(rendered.html()).contains("window.powercmsAnalyticsConfig");
        assertThat(rendered.html()).contains(AnalyticsInjector.DEFAULT_SCRIPT_URL);
        assertThat(countOccurrences(rendered.html(), "powercmsAnalyticsConfig")).isEqualTo(1);
        assertThat(rendered.html().indexOf("powercmsAnalyticsConfig"))
            .isLessThan(rendered.html().toLowerCase().lastIndexOf("</body>"));
    }

    @Test
    void previewPageSkipsInjection() {
        when(compositionService.zones(pageId)).thenReturn(Map.of("footer", List.of(trackerComponent())));

        var rendered = service.renderPreview(siteId, "/news");

        assertThat(rendered.html()).doesNotContain("powercmsAnalyticsConfig");
    }

    @Test
    void pageWithoutTrackerHasNoInjection() {
        when(compositionService.zones(pageId)).thenReturn(Map.of("footer", List.of()));

        var rendered = service.renderPublished(siteId, "/news");

        assertThat(rendered.html()).doesNotContain("powercmsAnalyticsConfig");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }

    /** 统计注入器需要按站点解析 siteKey：提供带 siteKey 的站点仓储桩。 */
    private static com.powercms.cms.site.repo.SiteRepository analyticsSiteRepo() {
        var repo = org.mockito.Mockito.mock(com.powercms.cms.site.repo.SiteRepository.class);
        var site = new com.powercms.cms.site.entity.SiteEntity();
        site.setSiteKey("demo");
        org.mockito.Mockito.lenient()
            .when(repo.findById(org.mockito.ArgumentMatchers.any(java.util.UUID.class)))
            .thenReturn(java.util.Optional.of(site));
        return repo;
    }
}
