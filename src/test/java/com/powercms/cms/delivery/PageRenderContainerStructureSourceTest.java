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
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitepackage.ComponentInstance;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.ThemePackageService;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.entity.SiteContainerEntity;
import com.powercms.cms.sitepackage.entity.SiteContainerStructureEntity;
import com.powercms.cms.sitepackage.entity.SiteDropZoneEntity;
import com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerStructureRepository;
import com.powercms.cms.sitepackage.repo.SiteDropZoneRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷回归：容器结构渲染源（{@code site_container_structure.code}）的**双形态契约**。
 *
 * <p>该列既承载**内联 FTL 源码**（Containers 编辑器 / TravelLux 预置），也被外部客户端用来写
 * **{@code .ftl} 模板文件引用**（与 {@code ComponentInstance.templatePath} 同义，如
 * {@code templates/comp-hero.ftl}）。历史上渲染侧无条件把该值当 FTL 源码交给
 * {@link FreeMarkerSandbox}，路径因不含 FTL 语法而**被逐字输出**到投放区里（页面出现裸路径文本）。
 * 现在路径形态先经 {@code tryResolveTemplateSource} 解析为文件内容；解析不到时回退且**绝不放行路径串**。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageRenderContainerStructureSourceTest {

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
    private TemplateService.ProjInfo project;

    private final UUID siteId = UUID.randomUUID();
    private final UUID pageId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();
    private final UUID containerId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final UUID pageTypeId = UUID.randomUUID();

    private static final String FRAGMENT_PATH = "templates/comp-hero.ftl";
    private static final String FRAGMENT_BODY = "<section class=\"pc-hero\">FRAGMENT-FILE-BODY</section>";

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

        PageVisualCompositionEntity composition = new PageVisualCompositionEntity();
        composition.setPageId(pageId);
        composition.setTemplateId(templateId);
        lenient().when(compositionRepo.findByPageId(pageId)).thenReturn(Optional.of(composition));

        SiteDropZoneEntity zone = new SiteDropZoneEntity();
        zone.setZoneKey("main");
        zone.setTemplateId(templateId);
        zone.setContainerId(containerId);
        lenient().when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(templateId)).thenReturn(List.of(zone));

        SiteContainerEntity container = new SiteContainerEntity();
        container.setId(containerId);
        container.setSiteId(siteId);
        container.setName("c-hero");
        container.setConfig("{\"type\":\"CONTENT\"}");
        lenient().when(containerRepo.findById(containerId)).thenReturn(Optional.of(container));
        lenient().when(containerModelFactory.create(any(), any(), any(), any(), anyBoolean()))
            .thenReturn(Map.of());

        project = new TemplateService.ProjInfo(
            UUID.randomUUID(), siteId, "powercom-fragments", "FTL", null, 1, Instant.now());
        lenient().when(templateService.listProjects(siteId)).thenReturn(List.of(project));
        lenient().when(templateService.readFileContent(project.id(), "site/page.ftl"))
            .thenReturn("<html><body>${renderedZones.main.html}</body></html>");

        SiteEntity site = new SiteEntity();
        site.setId(siteId);
        site.setSiteKey("powercom");
        site.setName("PowerCom");
        site.setDefaultLocale("zh-CN");
        site.setTimezone("Asia/Shanghai");
        site.setStatus("ACTIVE");
        lenient().when(sites.findById(siteId)).thenReturn(Optional.of(site));
        lenient().when(objectPermissions.hasAnonymousRight(any(), any(), any())).thenReturn(true);

        // 沙箱：页面渲染拼接投放区 HTML；容器项渲染**直接回显被要求渲染的 FTL 源**，
        // 于是「沙箱实际拿到的是文件内容还是裸路径」会如实反映在输出 HTML 上。
        lenient().when(freeMarkerSandbox.render(anyString(), anyString(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> model = inv.getArgument(2);
                if (model.containsKey("renderedZones")) {
                    Object zoneHtml = ((Map<?, ?>) model.get("renderedZones")).get("main") == null ? ""
                        : ((Map<?, ?>) ((Map<?, ?>) model.get("renderedZones")).get("main")).get("html");
                    return "<html><body>" + zoneHtml + "</body></html>";
                }
                Object source = inv.getArgument(1);
                return source == null ? "" : source.toString();
            });
    }

    private void structureCode(String code) {
        SiteContainerStructureEntity link = new SiteContainerStructureEntity();
        link.setContainerId(containerId);
        link.setStructureId(structureId);
        link.setCode(code);
        when(containerStructureRepo.findByContainerIdOrderBySortOrderAsc(containerId))
            .thenReturn(List.of(link));
    }

    /** EMBEDDED 实例、**不带** templatePath → 走容器结构渲染源分支。 */
    private ComponentInstance embeddedComponent() {
        return new ComponentInstance("c-hero-1", ComponentInstance.Kind.EMBEDDED, null, null,
            structureId, "首页", Map.of("title_s", "首页"), null, null, Map.of(), 0,
            null, Map.of(), null, null);
    }

    private List<String> sourcesHandedToSandboxFor(String renderNameFragment) {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sources = ArgumentCaptor.forClass(String.class);
        verify(freeMarkerSandbox, atLeastOnce()).render(names.capture(), sources.capture(), any(), anyBoolean());
        List<String> matched = new ArrayList<>();
        List<String> allNames = names.getAllValues();
        List<String> allSources = sources.getAllValues();
        for (int i = 0; i < allNames.size(); i++) {
            if (allNames.get(i).contains(renderNameFragment)) matched.add(allSources.get(i));
        }
        return matched;
    }

    // ---- 形态 ②：`.ftl` 模板文件引用 → 解析为文件内容，路径串不得出现在页面里 ----

    @Test
    void pathFormStructureCodeResolvesReferencedFtlFile() {
        structureCode(FRAGMENT_PATH);
        when(compositionService.zones(pageId)).thenReturn(Map.of("main", List.of(embeddedComponent())));
        when(templateService.readFileContent(project.id(), FRAGMENT_PATH)).thenReturn(FRAGMENT_BODY);

        String html = service.renderPreview(siteId, "/public").html();

        assertThat(sourcesHandedToSandboxFor("container-item")).containsExactly(FRAGMENT_BODY);
        assertThat(html).contains("FRAGMENT-FILE-BODY");
        assertThat(html).doesNotContain(FRAGMENT_PATH);
    }

    @Test
    void leadingSlashPathFormStructureCodeResolvesReferencedFtlFile() {
        structureCode("/" + FRAGMENT_PATH);
        when(compositionService.zones(pageId)).thenReturn(Map.of("main", List.of(embeddedComponent())));
        when(templateService.readFileContent(project.id(), FRAGMENT_PATH)).thenReturn(FRAGMENT_BODY);

        String html = service.renderPreview(siteId, "/public").html();

        assertThat(sourcesHandedToSandboxFor("container-item")).containsExactly(FRAGMENT_BODY);
        assertThat(html).doesNotContain(FRAGMENT_PATH);
    }

    @Test
    void unresolvablePathFormNeverLeaksPathIntoOutput() {
        structureCode(FRAGMENT_PATH);
        when(compositionService.zones(pageId)).thenReturn(Map.of("main", List.of(embeddedComponent())));
        when(templateService.readFileContent(project.id(), FRAGMENT_PATH))
            .thenThrow(new BusinessException(ErrorCodes.NOT_FOUND, "模板文件不存在"));

        String html = service.renderPreview(siteId, "/public").html();

        assertThat(sourcesHandedToSandboxFor("container-item")).isEmpty();
        assertThat(html).doesNotContain(FRAGMENT_PATH);
        // 回退到既有形态：无壳可渲染时输出标题占位
        assertThat(html).contains("<div class=\"component\">首页</div>");
    }

    // ---- 形态 ①：内联 FTL 源码契约保持不变 ----

    @Test
    void inlineFtlSourceStructureCodeIsPassedVerbatimToSandbox() {
        String inline = "<div class=\"content-item\">${content.title_s!}</div>";
        structureCode(inline);
        when(compositionService.zones(pageId)).thenReturn(Map.of("main", List.of(embeddedComponent())));

        service.renderPreview(siteId, "/public");

        assertThat(sourcesHandedToSandboxFor("container-item")).containsExactly(inline);
    }

    // ---- 实例自带 templatePath → 仍优先走 renderInstance（既有绕过手段保持有效）----

    @Test
    void instanceTemplatePathStillTakesRenderInstanceBranch() {
        structureCode(FRAGMENT_PATH);
        ComponentInstance instance = new ComponentInstance("c-hero-1", ComponentInstance.Kind.EMBEDDED, null, null,
            structureId, "首页", Map.of("title_s", "首页"), FRAGMENT_PATH, null, Map.of(), 0,
            null, Map.of(), null, null);
        when(compositionService.zones(pageId)).thenReturn(Map.of("main", List.of(instance)));
        when(componentRenderer.renderInstance(any(), any(), any(), any(), anyBoolean(), any(), anyInt(), anyBoolean(), isNull()))
            .thenReturn("<div>INSTANCE-RENDERED</div>");

        String html = service.renderPreview(siteId, "/public").html();

        assertThat(html).contains("INSTANCE-RENDERED");
        assertThat(sourcesHandedToSandboxFor("container-item")).isEmpty();
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
