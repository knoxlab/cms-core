package com.powercms.cms.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.ThemePackageService;
import com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerStructureRepository;
import com.powercms.cms.sitepackage.repo.SiteDropZoneRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.delivery.navigation.NavBreadcrumbBuilder;
import com.powercms.cms.delivery.navigation.NavTreeBuilder;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * FR-DELIVERY-PERM-001：SSR 管线匿名权限审查测试。
 * 验证 PageRenderService 在非预览模式下对已发布内容执行 hasAnonymousRight 检查，
 * 与 DeliveryService 行为一致。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageRenderPermissionTest {

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

        // 通用 mock：页面存在
        ContentItemEntity page = new ContentItemEntity();
        page.setId(pageId);
        page.setSiteId(siteId);
        page.setRoutePath("/restricted");
        page.setTitle("受限页面");
        page.setStatus("PUBLISHED");
        page.setTemplatePath("/site/page.ftl");
        page.setContentTypeId(UUID.randomUUID());
        page.setCurrentVersion(1);
        page.setPublishedVersion(1);
        lenient().when(itemRepo.findBySiteIdAndRoutePathAndStatus(siteId, "/restricted", "PUBLISHED"))
            .thenReturn(Optional.of(page));
        lenient().when(itemRepo.findBySiteId(siteId)).thenReturn(List.of(page));

        ContentVersionEntity version = ContentVersionEntity.create(pageId, 1, "{}", 1, "test", null);
        lenient().when(versionRepo.findByContentItemIdAndVersion(pageId, 1))
            .thenReturn(Optional.of(version));

        ContentTypeEntity pageType = new ContentTypeEntity();
        pageType.setObjectType("PAGE");
        lenient().when(typeRepo.findById(page.getContentTypeId())).thenReturn(Optional.of(pageType));

        lenient().when(freeMarkerSandbox.render(anyString(), anyString(), any(), anyBoolean()))
            .thenReturn("<html><body>restricted</body></html>");
        lenient().when(compositionRepo.findByPageId(pageId)).thenReturn(Optional.empty());

        TemplateService.ProjInfo project = new TemplateService.ProjInfo(
            UUID.randomUUID(), siteId, "main", "FTL", null, 1, Instant.now());
        lenient().when(templateService.listProjects(siteId)).thenReturn(List.of(project));
        lenient().when(templateService.readFileContent(project.id(), "site/page.ftl"))
            .thenReturn("<h1>${contentModel.title!''}</h1>");

        SiteEntity site = new SiteEntity();
        site.setId(siteId);
        site.setSiteKey("demo");
        site.setName("演示站");
        site.setDefaultLocale("zh-CN");
        site.setTimezone("Asia/Shanghai");
        site.setStatus("ACTIVE");
        lenient().when(sites.findById(siteId)).thenReturn(Optional.of(site));
    }

    // ---- 核心场景：匿名无权限 → 抛出 BusinessException ----

    @Test
    void publishedPageDeniedToAnonymousThrowsNotFound() {
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, pageId, ObjectType.VIEW)).thenReturn(false);

        assertThrows(BusinessException.class,
            () -> service.renderPublished(siteId, "/restricted"));
    }

    // ---- 核心场景：匿名有权限 → 正常渲染 ----

    @Test
    void publishedPageAllowedToAnonymousRendersNormally() {
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, pageId, ObjectType.VIEW)).thenReturn(true);

        var result = assertDoesNotThrow(
            () -> service.renderPublished(siteId, "/restricted"));

        assertThat(result.html()).contains("restricted");
        assertThat(result.id()).isEqualTo(pageId);
    }

    // ---- 未打破继承（默认公开）→ 正常渲染 ----

    @Test
    void unbrokenInheritanceAllowsAccess() {
        // hasAnonymousRight 默认返回 true（未打破继承时）
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, pageId, ObjectType.VIEW)).thenReturn(true);

        var result = service.renderPublished(siteId, "/restricted");
        assertThat(result.status()).isEqualTo("PUBLISHED");
    }

    // ---- 预览模式：跳过权限检查 ----

    @Test
    void previewModeSkipsPermissionCheck() {
        // 即使匿名无权限，预览模式也不应拦截
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, pageId, ObjectType.VIEW)).thenReturn(false);

        // renderPreview 调用 render(requiredStatus=null)，跳过权限检查
        var result = assertDoesNotThrow(
            () -> service.renderPreview(siteId, "/restricted"));

        assertThat(result.html()).contains("restricted");
    }

    // ---- Release 构建：跳过权限检查（回归：受限页不得阻断整站冻结发布）----

    @Test
    void releaseBuildWithThemeContextDoesNotRunPermissionCheck() {
        // 即使匿名无权限，Release 冻结渲染也不应拦截
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, pageId, ObjectType.VIEW)).thenReturn(false);

        var context = com.powercms.cms.publish.ReleaseThemeContext.from(List.of());
        var result = assertDoesNotThrow(
            () -> service.renderPublishedForRelease(siteId, "/restricted", context));

        assertThat(result.html()).contains("restricted");
    }

    @Test
    void releaseBuildWithoutThemeContextDoesNotRunPermissionCheck() {
        // 页面无主题上下文时 ReleaseService.freeze 走 renderForRelease（context==null 分支）
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, pageId, ObjectType.VIEW)).thenReturn(false);

        var result = assertDoesNotThrow(
            () -> service.renderForRelease(siteId, "/restricted"));

        assertThat(result.html()).contains("restricted");
    }

    // ---- 权限拒绝时不泄露内容 ----

    @Test
    void deniedPageDoesNotLeakContent() {
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, pageId, ObjectType.VIEW)).thenReturn(false);

        try {
            service.renderPublished(siteId, "/restricted");
        } catch (BusinessException ex) {
            // 错误消息不应包含页面标题或内容
            assertThat(ex.getMessage()).doesNotContain("受限页面");
            assertThat(ex.getMessage()).doesNotContain("restricted");
        }
    }

    // ---- 不同内容独立检查权限 ----

    @Test
    void differentContentItemsCheckedIndependently() {
        UUID publicId = UUID.randomUUID();
        ContentItemEntity publicPage = new ContentItemEntity();
        publicPage.setId(publicId);
        publicPage.setSiteId(siteId);
        publicPage.setRoutePath("/public");
        publicPage.setTitle("公开页面");
        publicPage.setStatus("PUBLISHED");
        publicPage.setTemplatePath("/site/page.ftl");
        publicPage.setContentTypeId(UUID.randomUUID());
        publicPage.setCurrentVersion(1);
        publicPage.setPublishedVersion(1);

        lenient().when(itemRepo.findBySiteIdAndRoutePathAndStatus(siteId, "/public", "PUBLISHED"))
            .thenReturn(Optional.of(publicPage));
        lenient().when(versionRepo.findByContentItemIdAndVersion(publicId, 1))
            .thenReturn(Optional.of(ContentVersionEntity.create(publicId, 1, "{}", 1, "t", null)));
        ContentTypeEntity type = new ContentTypeEntity();
        type.setObjectType("PAGE");
        lenient().when(typeRepo.findById(publicPage.getContentTypeId())).thenReturn(Optional.of(type));

        // 受限页面无权限
        when(objectPermissions.hasAnonymousRight(ObjectType.CONTENT, pageId, ObjectType.VIEW))
            .thenReturn(false);
        // 公开页面有权限
        when(objectPermissions.hasAnonymousRight(ObjectType.CONTENT, publicId, ObjectType.VIEW))
            .thenReturn(true);

        assertThrows(BusinessException.class,
            () -> service.renderPublished(siteId, "/restricted"));
        assertDoesNotThrow(() -> service.renderPublished(siteId, "/public"));
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
