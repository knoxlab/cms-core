package com.powercms.cms.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.navigation.NavBreadcrumbBuilder;
import com.powercms.cms.delivery.navigation.NavTreeBuilder;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.ThemePackageService;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.entity.SiteTemplateEntity;
import com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerStructureRepository;
import com.powercms.cms.sitepackage.repo.SiteDropZoneRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 计划 A（渲染源自足）：当代码模板工程中没有命中的 {@code .ftl} 时，页面必须能回退到其绑定的
 * 可视化模板（{@code site_template}）渲染——CODE 模式以 {@code layout_json.code} 作正文，
 * VISUAL 模式以布局 + 投放区包裹正文，不再把 {@code .ftl} 当作硬前置。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageRenderVisualFallbackTest {

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
    @Mock ObjectPermissionService objectPermissions;

    private PageRenderService service;
    private final UUID siteId = UUID.randomUUID();
    private final UUID pageId = UUID.randomUUID();
    private final UUID pageTypeId = UUID.randomUUID();
    private final UUID visualTemplateId = UUID.randomUUID();
    private final String route = "/fallback";

    @BeforeEach
    void setUp() {
        RenderDatasetCache cache = new RenderDatasetCache(16);
        RenderContextFactory contextFactory = new RenderContextFactory(
            sites, categories, itemRepo, versionRepo, typeRepo, nav, crumbs, urls, "test");
        ContainerTemplateModelFactory containerModelFactory =
            org.mockito.Mockito.mock(ContainerTemplateModelFactory.class);
        service = new PageRenderService(itemRepo, versionRepo, typeRepo, templateService,
            new ObjectMapper(), compositionService, freeMarkerSandbox, compositionRepo,
            zoneRepo, containerRepo, containerStructureRepo, cache, visualTemplateRepo,
            themePackageService, componentRenderer, contextFactory, containerModelFactory,
            new AnalyticsInjector(typeRepo, analyticsSiteRepo()),
            objectPermissions, new com.powercms.cms.identity.permission.ViewerAccessService(objectPermissions, org.mockito.Mockito.mock(com.powercms.cms.identity.repo.UserRepository.class)), "https://example.com");
        ContentTypeEntity pageType = new ContentTypeEntity();
        pageType.setObjectType("PAGE");
        lenient().when(typeRepo.findById(pageTypeId)).thenReturn(Optional.of(pageType));

        SiteEntity site = new SiteEntity();
        site.setId(siteId);
        site.setSiteKey("demo");
        site.setName("演示站");
        site.setDefaultLocale("zh-CN");
        site.setTimezone("Asia/Shanghai");
        site.setStatus("ACTIVE");
        lenient().when(sites.findById(siteId)).thenReturn(Optional.of(site));
        lenient().when(objectPermissions.hasAnonymousRight(ObjectType.CONTENT, pageId, ObjectType.VIEW))
            .thenReturn(true);
        lenient().when(compositionService.zones(pageId)).thenReturn(Map.of());
        lenient().when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(visualTemplateId)).thenReturn(List.of());
    }

    @Test
    void rendersFromVisualLayoutWhenFtlTemplateIsMissing() {
        page("/missing/page.ftl");
        // 站点没有任何 FTL 工程 → .ftl 解析必然落空
        when(templateService.listProjects(siteId)).thenReturn(List.of());
        bindVisualTemplate("{\"mode\":\"VISUAL\",\"columns\":12,"
            + "\"rows\":[{\"classes\":\"hero-row\",\"boxes\":[{\"width\":12,\"classes\":\"main-box\"}]}]}");

        var rendered = service.renderPublished(siteId, route);

        assertThat(rendered.html()).contains("hero-row").contains("main-box");
        assertThat(rendered.templatePath()).isEqualTo("visual:" + visualTemplateId);
    }

    @Test
    void rendersCodeBodyFromVisualTemplateWhenFtlTemplateIsMissing() {
        page("/missing/page.ftl");
        when(templateService.listProjects(siteId)).thenReturn(List.of());
        bindVisualTemplate("{\"mode\":\"CODE\",\"code\":\"<h1>${content.title}</h1>\"}");
        when(freeMarkerSandbox.render(eq("visual-template-" + visualTemplateId + "-body.ftl"),
            anyString(), any(), anyBoolean())).thenReturn("<h1>CODE-FALLBACK</h1>");

        var rendered = service.renderPublished(siteId, route);

        assertThat(rendered.html()).contains("CODE-FALLBACK");
    }

    @Test
    void rendersFromVisualTemplateWhenNoTemplatePathConfigured() {
        page(null);
        when(templateService.listProjects(siteId)).thenReturn(List.of());
        bindVisualTemplate("{\"mode\":\"VISUAL\",\"rows\":[{\"classes\":\"only-row\","
            + "\"boxes\":[{\"width\":12}]}]}");

        var rendered = service.renderPublished(siteId, route);

        assertThat(rendered.html()).contains("only-row");
    }

    @Test
    void ftlTemplateStillTakesPrecedenceWhenPresent() {
        page("/site/page.ftl");
        TemplateService.ProjInfo project = new TemplateService.ProjInfo(
            UUID.randomUUID(), siteId, "main", "FTL", null, 1, Instant.now());
        when(templateService.listProjects(siteId)).thenReturn(List.of(project));
        when(templateService.readFileContent(project.id(), "site/page.ftl"))
            .thenReturn("<html><body>FTL-SOURCE</body></html>");
        when(freeMarkerSandbox.render(anyString(), anyString(), any(), anyBoolean()))
            .thenReturn("FTL-RENDERED");

        var rendered = service.renderPublished(siteId, route);

        assertThat(rendered.html()).contains("FTL-RENDERED");
        assertThat(rendered.templatePath()).isEqualTo("site/page.ftl");
    }

    @Test
    void failsWhenNeitherFtlNorVisualTemplateCanRender() {
        page("/missing/page.ftl");
        when(templateService.listProjects(siteId)).thenReturn(List.of());
        lenient().when(compositionRepo.findByPageId(pageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renderPublished(siteId, route))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("页面模板不存在");
    }

    /**
     * DEF-DELIVERY-01：交付层「资源缺失」必须是 NOT_FOUND，否则控制器会把 404 场景映射成 500
     * （FR-DELIVERY-PERM-001 的语义前提）。
     */
    @Test
    void missingPublishedPageIsNotFound() {
        when(itemRepo.findBySiteIdAndRoutePathAndStatus(siteId, route, "PUBLISHED"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renderPublished(siteId, route))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(com.powercms.common.error.ErrorCodes.NOT_FOUND);
    }

    @Test
    void missingTemplateIsNotFound() {
        page("/missing/page.ftl");
        when(templateService.listProjects(siteId)).thenReturn(List.of());
        lenient().when(compositionRepo.findByPageId(pageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renderPublished(siteId, route))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(com.powercms.common.error.ErrorCodes.NOT_FOUND);
    }

    @Test
    void missingContentVersionIsNotFound() {
        page("/site/page.ftl");
        when(versionRepo.findByContentItemIdAndVersion(pageId, 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renderPublished(siteId, route))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(com.powercms.common.error.ErrorCodes.NOT_FOUND);
    }

    private void page(String templatePath) {
        ContentItemEntity page = new ContentItemEntity();
        page.setId(pageId);
        page.setSiteId(siteId);
        page.setRoutePath(route);
        page.setTitle("回退页");
        page.setStatus("PUBLISHED");
        page.setTemplatePath(templatePath);
        page.setContentTypeId(pageTypeId);
        page.setCurrentVersion(1);
        page.setPublishedVersion(1);
        when(itemRepo.findBySiteIdAndRoutePathAndStatus(siteId, route, "PUBLISHED"))
            .thenReturn(Optional.of(page));
        when(versionRepo.findByContentItemIdAndVersion(pageId, 1))
            .thenReturn(Optional.of(ContentVersionEntity.create(pageId, 1, "{}", 1, "t", null)));
    }

    private void bindVisualTemplate(String layoutJson) {
        PageVisualCompositionEntity composition = new PageVisualCompositionEntity();
        composition.setPageId(pageId);
        composition.setTemplateId(visualTemplateId);
        when(compositionRepo.findByPageId(pageId)).thenReturn(Optional.of(composition));

        SiteTemplateEntity visual = new SiteTemplateEntity();
        visual.setId(visualTemplateId);
        visual.setSiteId(siteId);
        visual.setName("主页模板");
        visual.setLayoutJson(layoutJson);
        visual.setThemeId(null);
        when(visualTemplateRepo.findById(visualTemplateId)).thenReturn(Optional.of(visual));
    }

    private void bindVisualTemplateWithTheme(String layoutJson, UUID themeId) {
        PageVisualCompositionEntity composition = new PageVisualCompositionEntity();
        composition.setPageId(pageId);
        composition.setTemplateId(visualTemplateId);
        when(compositionRepo.findByPageId(pageId)).thenReturn(Optional.of(composition));

        SiteTemplateEntity visual = new SiteTemplateEntity();
        visual.setId(visualTemplateId);
        visual.setSiteId(siteId);
        visual.setName("主页模板");
        visual.setLayoutJson(layoutJson);
        visual.setThemeId(themeId);
        when(visualTemplateRepo.findById(visualTemplateId)).thenReturn(Optional.of(visual));
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
