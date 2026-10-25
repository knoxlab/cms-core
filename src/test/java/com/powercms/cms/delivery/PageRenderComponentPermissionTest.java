package com.powercms.cms.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.navigation.NavBreadcrumbBuilder;
import com.powercms.cms.delivery.navigation.NavTreeBuilder;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.ComponentInstance;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.ThemePackageService;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.entity.SiteContainerEntity;
import com.powercms.cms.sitepackage.entity.SiteDropZoneEntity;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-DELIVERY-PERM-001 §3.5：组件/投放级内容 ACL 过滤测试。
 * 公开页投放区内的「内容型组件」若引用 ACL 受限内容，匿名交付（SSR）下该组件必须被剔除，
 * 不得把受限字段值输出进公开 HTML；预览模式不受影响。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageRenderComponentPermissionTest {

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
    private final UUID restrictedId = UUID.randomUUID();
    private final UUID publicId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();
    private final UUID containerId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final UUID pageTypeId = UUID.randomUUID();

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

        // ---- 顶层页面 ----
        ContentItemEntity page = new ContentItemEntity();
        page.setId(pageId);
        page.setSiteId(siteId);
        page.setRoutePath("/public");
        page.setTitle("公开页");
        page.setStatus("PUBLISHED");
        page.setTemplatePath("/site/page.ftl");
        page.setContentTypeId(pageTypeId);
        page.setCurrentVersion(1);
        page.setPublishedVersion(1);
        lenient().when(itemRepo.findBySiteIdAndRoutePathAndStatus(siteId, "/public", "PUBLISHED"))
            .thenReturn(Optional.of(page));
        lenient().when(itemRepo.findBySiteId(siteId)).thenReturn(List.of(page));
        lenient().when(versionRepo.findByContentItemIdAndVersion(pageId, 1))
            .thenReturn(Optional.of(ContentVersionEntity.create(pageId, 1, "{}", 1, "t", null)));

        ContentTypeEntity pageType = new ContentTypeEntity();
        pageType.setObjectType("PAGE");
        lenient().when(typeRepo.findById(pageTypeId)).thenReturn(Optional.of(pageType));

        // ---- 页面可视化布局（投放区 + 容器）----
        PageVisualCompositionEntity composition = new PageVisualCompositionEntity();
        composition.setPageId(pageId);
        composition.setTemplateId(templateId);
        lenient().when(compositionRepo.findByPageId(pageId)).thenReturn(Optional.of(composition));

        SiteDropZoneEntity zone = new SiteDropZoneEntity();
        zone.setZoneKey("main");
        zone.setTemplateId(templateId);
        zone.setContainerId(containerId);
        lenient().when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(templateId))
            .thenReturn(List.of(zone));

        SiteContainerEntity container = new SiteContainerEntity();
        container.setId(containerId);
        container.setSiteId(siteId);
        container.setName("main");
        container.setConfig("{\"type\":\"CONTENT\",\"displayTemplates\":{\""
            + structureId + "\":\"item-snippet\"}}");
        lenient().when(containerRepo.findById(containerId)).thenReturn(Optional.of(container));
        lenient().when(containerStructureRepo.findByContainerIdOrderBySortOrderAsc(containerId))
            .thenReturn(List.of());
        lenient().when(containerModelFactory.create(any(), any(), any(), any(), anyBoolean()))
            .thenReturn(Map.of());

        // ---- 模板文件 ----
        TemplateService.ProjInfo project = new TemplateService.ProjInfo(
            UUID.randomUUID(), siteId, "main", "FTL", null, 1, Instant.now());
        lenient().when(templateService.listProjects(siteId)).thenReturn(List.of(project));
        lenient().when(templateService.readFileContent(project.id(), "site/page.ftl"))
            .thenReturn("<html><body>${renderedZones.main.html}</body></html>");

        // ---- 两个被引用的内容（受限/公开）：item.title 由渲染代码回填到内容模型 ----
        ContentItemEntity restricted = contentItem(restrictedId, "RESTRICTED-ITEM-TITLE");
        lenient().when(itemRepo.findById(restrictedId)).thenReturn(Optional.of(restricted));
        lenient().when(versionRepo.findByContentItemIdAndVersion(restrictedId, 1))
            .thenReturn(Optional.of(ContentVersionEntity.create(restrictedId, 1,
                "{\"title\":\"RESTRICTED-ITEM-TITLE\",\"secret_s\":\"restricted-secret\"}", 1, "t", null)));

        ContentItemEntity open = contentItem(publicId, "PUBLIC-ITEM-TITLE");
        lenient().when(itemRepo.findById(publicId)).thenReturn(Optional.of(open));
        lenient().when(versionRepo.findByContentItemIdAndVersion(publicId, 1))
            .thenReturn(Optional.of(ContentVersionEntity.create(publicId, 1,
                "{\"title\":\"PUBLIC-ITEM-TITLE\"}", 1, "t", null)));

        // ---- 沙箱：容器项渲染输出内容 map（模拟模板回显任意字段）；页面渲染拼接投放区 HTML ----
        lenient().when(freeMarkerSandbox.render(anyString(), anyString(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> model = inv.getArgument(2);
                if (model.containsKey("renderedZones")) {
                    Object zoneHtml = ((Map<?, ?>) model.get("renderedZones")).containsKey("main")
                        ? ((Map<?, ?>) ((Map<?, ?>) model.get("renderedZones")).get("main")).get("html")
                        : "";
                    return "<html><body>" + zoneHtml + "</body></html>";
                }
                if (model.get("content") instanceof Map<?, ?> content
                    && content.get("title") != null) {
                    return "<p>FIELDS:" + content + "</p>";
                }
                return "";
            });

        SiteEntity site = new SiteEntity();
        site.setId(siteId);
        site.setSiteKey("demo");
        site.setName("演示站");
        site.setDefaultLocale("zh-CN");
        site.setTimezone("Asia/Shanghai");
        site.setStatus("ACTIVE");
        lenient().when(sites.findById(siteId)).thenReturn(Optional.of(site));

        // 默认：页面自身与公开内容可匿名访问；受限内容默认也在 setUp 放行，测试按需收紧
        lenient().when(objectPermissions.hasAnonymousRight(ObjectType.CONTENT, pageId, ObjectType.VIEW))
            .thenReturn(true);
        lenient().when(objectPermissions.hasAnonymousRight(ObjectType.CONTENT, publicId, ObjectType.VIEW))
            .thenReturn(true);
        lenient().when(objectPermissions.hasAnonymousRight(ObjectType.CONTENT, restrictedId, ObjectType.VIEW))
            .thenReturn(true);
    }

    private ContentItemEntity contentItem(UUID id, String title) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(id);
        item.setSiteId(siteId);
        item.setTitle(title);
        item.setStatus("PUBLISHED");
        item.setTemplatePath("/site/page.ftl");
        item.setContentTypeId(UUID.randomUUID());
        item.setCurrentVersion(1);
        item.setPublishedVersion(1);
        ContentTypeEntity type = new ContentTypeEntity();
        type.setObjectType("ARTICLE");
        lenient().when(typeRepo.findById(item.getContentTypeId())).thenReturn(Optional.of(type));
        return item;
    }

    private ComponentInstance contentComponent(String id, UUID contentId) {
        return new ComponentInstance(id, ComponentInstance.Kind.CONTENT, null, null,
            structureId, "标题", Map.of(), null, null, Map.of(), 0,
            null, Map.of(), contentId, null);
    }

    // ---- 匿名交付：受限内容组件必须被剔除 ----

    @Test
    void anonymousDeliveryDropsRestrictedContentComponent() {
        when(compositionService.zones(pageId)).thenReturn(Map.of("main", List.of(
            contentComponent("c-restricted", restrictedId),
            contentComponent("c-public", publicId))));
        // 收紧：受限内容不可匿名访问
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, restrictedId, ObjectType.VIEW)).thenReturn(false);

        var rendered = service.renderPublished(siteId, "/public");
        String html = rendered.html();

        assertThat(html).contains("PUBLIC-ITEM-TITLE");
        assertThat(html).doesNotContain("RESTRICTED-ITEM-TITLE");
        assertThat(html).doesNotContain("restricted-secret");
        // 受限组件未被渲染（容器项渲染仅发生一次，来自公开组件）
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(freeMarkerSandbox, org.mockito.Mockito.atLeastOnce())
            .render(name.capture(), anyString(), any(), anyBoolean());
        assertThat(name.getAllValues())
            .noneMatch(n -> n.contains("c-restricted"));
    }

    // ---- 受限内容匿名可见 → 正常渲染 ----

    @Test
    void anonymousDeliveryRendersComponentWhenContentIsAllowed() {
        when(compositionService.zones(pageId)).thenReturn(Map.of("main", List.of(
            contentComponent("c-restricted", restrictedId),
            contentComponent("c-public", publicId))));

        var rendered = service.renderPublished(siteId, "/public");

        assertThat(rendered.html()).contains("RESTRICTED-ITEM-TITLE");
        assertThat(rendered.html()).contains("PUBLIC-ITEM-TITLE");
        assertThat(rendered.html()).contains("restricted-secret");
    }

    // ---- 预览模式：即使匿名受限也完整渲染（编辑预览不受交付 ACL 限制）----

    @Test
    void previewKeepsRestrictedComponentRegardlessOfAnonymousAcl() {
        when(compositionService.zones(pageId)).thenReturn(Map.of("main", List.of(
            contentComponent("c-restricted", restrictedId))));
        when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, restrictedId, ObjectType.VIEW)).thenReturn(false);

        var rendered = service.renderPreview(siteId, "/public");

        assertThat(rendered.html()).contains("RESTRICTED-ITEM-TITLE");
        assertThat(rendered.html()).contains("restricted-secret");
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
