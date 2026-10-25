package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.FreeMarkerSandbox;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.sitepackage.entity.SiteContainerEntity;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.entity.SiteContainerRevisionEntity;
import com.powercms.cms.sitepackage.entity.SiteDropZoneEntity;
import com.powercms.cms.sitepackage.entity.SiteTemplateEntity;
import com.powercms.cms.sitepackage.entity.SiteThemeEntity;
import com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerRevisionRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerStructureRepository;
import com.powercms.cms.sitepackage.repo.SiteDropZoneRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateAclRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRevisionRepository;
import com.powercms.cms.sitepackage.repo.SiteThemeRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisualLayoutServiceTest {
    @Mock SiteThemeRepository themeRepo;
    @Mock ThemePackageService themePackageService;
    @Mock SiteTemplateRepository templateRepo;
    @Mock SiteContainerRepository containerRepo;
    @Mock SiteContainerRevisionRepository containerRevisionRepo;
    @Mock SiteContainerStructureRepository containerStructureRepo;
    @Mock com.powercms.cms.model.repo.ContentTypeRepository contentTypeRepo;
    @Mock SiteDropZoneRepository zoneRepo;
    @Mock SiteTemplateRevisionRepository revisionRepo;
    @Mock SiteTemplateAclRepository aclRepo;
    @Mock PageVisualCompositionRepository compositionRepo;
    @Mock AuditService auditService;

    VisualLayoutService service;
    UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VisualLayoutService(themeRepo, themePackageService, templateRepo, containerRepo, containerRevisionRepo, containerStructureRepo, contentTypeRepo, zoneRepo,
            revisionRepo, aclRepo, compositionRepo, auditService, new RenderDatasetCache(64), new ObjectMapper(),
            new FreeMarkerSandbox());
    }


    @Test
    void consumerCanBindActiveSharedThemeAndUnauthorizedSiteCannot() {
        UUID themeId=UUID.randomUUID(); SiteThemeEntity theme=new SiteThemeEntity(); theme.setId(themeId); theme.setSiteId(UUID.randomUUID()); theme.setName("shared"); theme.setValidity("VALID");
        when(themePackageService.resolveAvailableTheme(siteId,themeId)).thenReturn(new ThemePackageService.AvailableTheme(theme,"SHARED",theme.getSiteId(),true,1));
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv->{SiteTemplateEntity e=inv.getArgument(0);e.setId(UUID.randomUUID());return e;});
        when(templateRepo.save(any())).thenAnswer(inv->inv.getArgument(0)); when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        var saved=service.saveTemplate(siteId,null,themeId,"Shared",null,"{}","DRAFT","consumer",null);
        assertThat(saved.themeId()).isEqualTo(themeId);
        UUID other=UUID.randomUUID(); when(themePackageService.resolveAvailableTheme(other,themeId)).thenThrow(new BusinessException(com.powercms.common.error.ErrorCodes.VALIDATION_FAILED,"not authorized"));
        assertThatThrownBy(()->service.saveTemplate(other,null,themeId,"No",null,"{}","DRAFT","other",null)).hasMessageContaining("not authorized");
    }

    @Test
    void detailLookupsEnforceSiteAvailabilityAndOwnership() {
        UUID themeId = UUID.randomUUID(), containerId = UUID.randomUUID(), otherSite = UUID.randomUUID();
        SiteThemeEntity theme = new SiteThemeEntity(); theme.setId(themeId); theme.setSiteId(siteId); theme.setName("Theme");
        when(themePackageService.resolveAvailableTheme(siteId, themeId)).thenReturn(
            new ThemePackageService.AvailableTheme(theme, "OWNED", siteId, false, null));
        assertThat(service.getTheme(siteId, themeId).id()).isEqualTo(themeId);
        when(themePackageService.resolveAvailableTheme(otherSite, themeId)).thenThrow(
            new BusinessException(com.powercms.common.error.ErrorCodes.NOT_FOUND, "not available"));
        assertThatThrownBy(() -> service.getTheme(otherSite, themeId)).isInstanceOf(BusinessException.class);

        SiteContainerEntity container = new SiteContainerEntity(); container.setId(containerId); container.setSiteId(siteId); container.setName("Container");
        when(containerRepo.findById(containerId)).thenReturn(Optional.of(container));
        assertThat(service.getContainer(siteId, containerId).id()).isEqualTo(containerId);
        assertThatThrownBy(() -> service.getContainer(otherSite, containerId)).isInstanceOf(BusinessException.class);
    }

    @Test
    void createsImmutableContainerRevisionAndRestoresAsNewVersion() {
        UUID containerId = UUID.randomUUID();
        SiteContainerEntity container = new SiteContainerEntity(); container.setId(containerId); container.setSiteId(siteId);
        container.setName("Hero"); container.setAllowedComponentTypes("[]"); container.setConfig("{\"type\":\"CONTENT\"}");
        when(containerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(containerRevisionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var created = service.saveContainer(siteId, null, "Hero", "[]", "{\"type\":\"CONTENT\"}", "editor", "create");
        assertThat(created.currentVersion()).isEqualTo(1);
        verify(containerRevisionRepo).save(any());

        SiteContainerRevisionEntity old = SiteContainerRevisionEntity.create(containerId, 1, null, "editor", "old",
            "{\"name\":\"Old Hero\",\"allowedComponentTypes\":[],\"config\":{\"type\":\"ZERO_CONTENT\",\"zeroContentFtl\":\"<div>empty</div>\"}}");
        container.setCurrentVersion(2);
        when(containerRepo.findById(containerId)).thenReturn(Optional.of(container));
        when(containerRevisionRepo.findByContainerIdAndVersion(containerId, 1)).thenReturn(Optional.of(old));

        var restored = service.restoreContainer(siteId, containerId, 1, "editor");
        assertThat(restored.name()).isEqualTo("Old Hero");
        assertThat(restored.currentVersion()).isEqualTo(3);
    }

    @Test
    void normalizesVisualLayoutAndCreatesRevision() {
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> {
            SiteTemplateEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(revisionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var info = service.saveTemplate(siteId, null, null, "Home", "desc",
            "{\"mode\":\"VISUAL\",\"header\":true,\"footer\":false,\"sidebar\":\"LEFT\",\"sidebarWidth\":20,\"rows\":[{\"boxes\":[{\"width\":8},{\"width\":4}]}]}",
            "DRAFT", "admin", "init");

        assertThat(info.layoutJson()).contains("\"columns\":12");
        assertThat(info.currentVersion()).isEqualTo(1);
        verify(revisionRepo).save(any());
    }

    @Test
    void rejectsRowWiderThanTwelve() {
        assertThatThrownBy(() -> service.saveTemplate(siteId, null, null, "Bad", null,
            "{\"mode\":\"VISUAL\",\"rows\":[{\"boxes\":[{\"width\":8},{\"width\":5}]}]}",
            "DRAFT", "admin", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能超过 12");
    }

    @Test
    void requiresCodeForCodeMode() {
        assertThatThrownBy(() -> service.saveTemplate(siteId, null, null, "Code", null,
            "{\"mode\":\"CODE\",\"code\":\"\"}", "DRAFT", "admin", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CODE 模式必须填写");
    }

    // ---- 计划 A：mode 显式优先 + 由 rows/code 派生（对齐 dotCMS drawed 语义）----

    @Test
    void derivesCodeModeWhenCodeProvidedWithoutExplicitMode() {
        stubTemplatePersist();
        var info = service.saveTemplate(siteId, null, null, "CodeOnly", null,
            "{\"code\":\"<h1>${content.title}</h1>\"}", "DRAFT", "admin", null);
        assertThat(info.layoutJson()).contains("\"mode\":\"CODE\"");
        assertThat(info.layoutJson()).contains("${content.title}");
    }

    @Test
    void derivesVisualModeWhenRowsProvidedWithoutExplicitMode() {
        stubTemplatePersist();
        var info = service.saveTemplate(siteId, null, null, "RowsOnly", null,
            "{\"rows\":[{\"boxes\":[{\"width\":12}]}]}", "DRAFT", "admin", null);
        assertThat(info.layoutJson()).contains("\"mode\":\"VISUAL\"");
    }

    @Test
    void explicitModeWinsOverDerivation() {
        stubTemplatePersist();
        var info = service.saveTemplate(siteId, null, null, "VisualWithCode", null,
            "{\"mode\":\"VISUAL\",\"rows\":[{\"boxes\":[{\"width\":12}]}],\"code\":\"ignored\"}",
            "DRAFT", "admin", null);
        assertThat(info.layoutJson()).contains("\"mode\":\"VISUAL\"");
    }

    private void stubTemplatePersist() {
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> {
            SiteTemplateEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(revisionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void allowsContainerReuseInSameTemplate() {
        UUID templateId = UUID.randomUUID();
        UUID containerId = UUID.randomUUID();
        SiteTemplateEntity template = template(templateId, siteId, "T");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template));
        when(containerRepo.findById(containerId)).thenReturn(Optional.of(container(containerId)));
        SiteDropZoneEntity existing = zone(UUID.randomUUID(), templateId, containerId, "main");
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(templateId))
            .thenReturn(List.of(existing), List.of(existing));
        when(zoneRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.saveZone(siteId, templateId, null, "sidebar", containerId, 10, "{}", "admin");

        verify(zoneRepo).save(any());
        assertThat(result.zones()).hasSize(1);
    }

    @Test
    void deletesOwnedZoneAndSnapshotsUpdatedTemplate() {
        UUID templateId = UUID.randomUUID(), zoneId = UUID.randomUUID();
        SiteTemplateEntity template = template(templateId, siteId, "T");
        SiteDropZoneEntity zone = zone(zoneId, templateId, UUID.randomUUID(), "main");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template));
        when(zoneRepo.findById(zoneId)).thenReturn(Optional.of(zone));
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(templateId)).thenReturn(List.of());
        when(compositionRepo.findByTemplateId(templateId)).thenReturn(List.of());

        var result = service.deleteZone(siteId, templateId, zoneId, "admin");

        verify(zoneRepo).delete(zone);
        verify(revisionRepo).save(any());
        verify(auditService).write(any(), any(), any(), any(), any(), any(), any());
        assertThat(result.zones()).isEmpty();
        assertThat(result.currentVersion()).isEqualTo(1);
    }

    @Test
    void rejectsDeletingZoneWithNonEmptyCompositionReference() {
        UUID templateId = UUID.randomUUID(), zoneId = UUID.randomUUID();
        SiteTemplateEntity template = template(templateId, siteId, "T");
        SiteDropZoneEntity zone = zone(zoneId, templateId, UUID.randomUUID(), "main");
        PageVisualCompositionEntity composition = composition(UUID.randomUUID(), templateId, "{\"main\":[{\"id\":1}]}");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template));
        when(zoneRepo.findById(zoneId)).thenReturn(Optional.of(zone));
        when(compositionRepo.findByTemplateId(templateId)).thenReturn(List.of(composition));

        assertThatThrownBy(() -> service.deleteZone(siteId, templateId, zoneId, "admin"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("1 \u4e2a\u9875\u9762").hasMessageContaining("1 \u4e2a\u9875\u9762\u7ec4\u5408");
        org.mockito.Mockito.verifyNoInteractions(auditService);
        org.mockito.Mockito.verify(zoneRepo, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void cleansEmptyCompositionReferenceBeforeDeletingZone() {
        UUID templateId = UUID.randomUUID(), zoneId = UUID.randomUUID();
        SiteTemplateEntity template = template(templateId, siteId, "T");
        SiteDropZoneEntity zone = zone(zoneId, templateId, UUID.randomUUID(), "main");
        PageVisualCompositionEntity composition = composition(UUID.randomUUID(), templateId, "{\"main\":[],\"other\":[1]}");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template));
        when(zoneRepo.findById(zoneId)).thenReturn(Optional.of(zone));
        when(compositionRepo.findByTemplateId(templateId)).thenReturn(List.of(composition));
        when(compositionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(templateId)).thenReturn(List.of());

        service.deleteZone(siteId, templateId, zoneId, "admin");

        assertThat(composition.getZonesJson()).isEqualTo("{\"other\":[1]}");
        assertThat(composition.getVersion()).isEqualTo(2);
        assertThat(composition.getUpdatedBy()).isEqualTo("admin");
        verify(compositionRepo).save(composition);
        verify(zoneRepo).delete(zone);
    }

    @Test
    void deletesZoneWithoutCompositions() {
        UUID templateId = UUID.randomUUID(), zoneId = UUID.randomUUID();
        SiteTemplateEntity template = template(templateId, siteId, "T");
        SiteDropZoneEntity zone = zone(zoneId, templateId, UUID.randomUUID(), "main");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template));
        when(zoneRepo.findById(zoneId)).thenReturn(Optional.of(zone));
        when(compositionRepo.findByTemplateId(templateId)).thenReturn(List.of());
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(templateId)).thenReturn(List.of());

        service.deleteZone(siteId, templateId, zoneId, "admin");

        verify(zoneRepo).delete(zone);
        org.mockito.Mockito.verify(compositionRepo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void rejectsDeletingZoneFromAnotherTemplate() {
        UUID templateId = UUID.randomUUID(), zoneId = UUID.randomUUID();
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template(templateId, siteId, "T")));
        when(zoneRepo.findById(zoneId)).thenReturn(Optional.of(
            zone(zoneId, UUID.randomUUID(), UUID.randomUUID(), "other")));

        assertThatThrownBy(() -> service.deleteZone(siteId, templateId, zoneId, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("\u4e0d\u5c5e\u4e8e\u5f53\u524d\u6a21\u677f");
    }

    @Test
    void normalizesContainerDisplayConfig() {
        when(containerRepo.save(any())).thenAnswer(inv -> {
            SiteContainerEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        var info = service.saveContainer(siteId, null, "Hero", "[]",
            "{\"type\":\"CONTENT\",\"preLoopFtl\":\"<ul>\",\"postLoopFtl\":\"</ul>\",\"displayTemplates\":{}}", "editor", null);
        assertThat(info.config()).contains("\"type\":\"CONTENT\"");
        assertThat(info.config()).contains("preLoopFtl");
    }

    // ---------- FreeMarker 默认值优先级陷阱：四个卡点均须保存即拒 ----------

    /** 卡点 1：容器 zeroContentFtl。 */
    @Test
    void rejectsContainerZeroContentFtlWithPrecedenceTrap() {
        String config = containerConfig(c -> c.put("zeroContentFtl",
            "<#if (it.desc_t)!'' != ''>${it.desc_t}</#if>"));

        assertThatThrownBy(() -> service.saveContainer(siteId, null, "Case", "[]", config, "editor", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("zeroContentFtl")
            .hasMessageContaining("优先级陷阱")
            .hasMessageContaining("NonBooleanException");
    }

    /** 卡点 1：容器 preLoopFtl / postLoopFtl 同样覆盖。 */
    @Test
    void rejectsContainerPreAndPostLoopFtlWithPrecedenceTrap() {
        assertThatThrownBy(() -> service.saveContainer(siteId, null, "Pre", "[]",
            containerConfig(c -> c.put("preLoopFtl", "<#if (a)!'' != ''>")), "editor", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("preLoopFtl");

        assertThatThrownBy(() -> service.saveContainer(siteId, null, "Post", "[]",
            containerConfig(c -> c.put("postLoopFtl", "<#if (a)!'' != ''>")), "editor", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("postLoopFtl");
    }

    /** 卡点 1：displayTemplates（组件类型 ID → FTL）逐项覆盖。 */
    @Test
    void rejectsContainerDisplayTemplateWithPrecedenceTrap() {
        String typeId = UUID.randomUUID().toString();
        String config = containerConfig(c -> c.putObject("displayTemplates")
            .put(typeId, "<#if (it.subtitle_s)!'' != ''>x</#if>"));

        assertThatThrownBy(() -> service.saveContainer(siteId, null, "Disp", "[]", config, "editor", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("displayTemplates[" + typeId + "]")
            .hasMessageContaining("优先级陷阱");
    }

    /** 修复后的正确形态必须放行（回归保护：别把正常容器一起挡住）。 */
    @Test
    void acceptsContainerFtlWithCorrectOuterParenthesization() {
        when(containerRepo.save(any())).thenAnswer(inv -> {
            SiteContainerEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        String ftl = "<#assign rows = items!contentQuery(\"case\", 10, \"sort\")>"
            + "<#list rows as it><#if ((it.desc_t)!'') != ''>${it.desc_t}</#if></#list>";

        var info = service.saveContainer(siteId, null, "Case", "[]",
            containerConfig(c -> c.put("zeroContentFtl", ftl)), "editor", null);

        assertThat(info.name()).isEqualTo("Case");
    }

    /** 卡点 2：容器结构 code（内联 FTL 形态）。 */
    @Test
    void rejectsContainerStructureCodeWithPrecedenceTrap() {
        UUID typeId = UUID.randomUUID();
        when(contentTypeRepo.findById(typeId)).thenReturn(Optional.of(contentType(typeId, "case-group")));
        String config = containerConfig(c -> c.put("maxContents", 5));

        assertThatThrownBy(() -> service.saveContainer(siteId, null, "Struct", config,
            List.of(new VisualLayoutService.ContainerStructureInput(typeId,
                "<#if (it.desc_t)!'' != ''>x</#if>", 0)), "editor", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("容器结构 code")
            .hasMessageContaining("case-group")
            .hasMessageContaining("优先级陷阱");
    }

    /** 卡点 2：容器结构 code 的另一种契约是 FTL 文件路径，路径必须放行。 */
    @Test
    void acceptsContainerStructureCodeThatIsTemplatePath() {
        UUID typeId = UUID.randomUUID();
        when(contentTypeRepo.findById(typeId)).thenReturn(Optional.of(contentType(typeId, "case-group")));
        when(containerRepo.save(any())).thenAnswer(inv -> {
            SiteContainerEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        String config = containerConfig(c -> c.put("maxContents", 5));

        var info = service.saveContainer(siteId, null, "Struct", config,
            List.of(new VisualLayoutService.ContainerStructureInput(typeId, "/templates/frag-case.ftl", 0)),
            "editor", null);

        assertThat(info.name()).isEqualTo("Struct");
    }

    /** 卡点 3：可视化模板的片段覆盖（fragmentOverrides，内联 FreeMarker）。 */
    @Test
    void rejectsVisualTemplateFragmentOverrideWithPrecedenceTrap() {
        String layout = visualLayout(null, null,
            java.util.Map.of("header", "<#if (c.tel_s)!'' != ''>x</#if>"));

        assertThatThrownBy(() -> service.saveTemplate(siteId, null, null, "T", null, layout, "DRAFT", "editor", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("片段覆盖 header")
            .hasMessageContaining("优先级陷阱");
    }

    /** 卡点 4：可视化模板 CODE 模式的内联模板代码。 */
    @Test
    void rejectsVisualTemplateCodeModeWithPrecedenceTrap() {
        String layout = visualLayout("CODE", "<#if (c.tel_s)!'' != ''>x</#if>", null);

        assertThatThrownBy(() -> service.saveTemplate(siteId, null, null, "T", null, layout, "DRAFT", "editor", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CODE 模式")
            .hasMessageContaining("优先级陷阱");
    }

    /** 可视化模板片段覆盖的正确写法必须放行。 */
    @Test
    void acceptsVisualTemplateFragmentOverrideWithCorrectParenthesization() {
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> {
            SiteTemplateEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        String layout = visualLayout(null, null,
            java.util.Map.of("header", "<#if ((c.tel_s)!'') != ''>x</#if>"));

        var info = service.saveTemplate(siteId, null, null, "T", null, layout, "DRAFT", "editor", null);

        assertThat(info.name()).isEqualTo("T");
    }

    /** 容器配置 JSON 构造器：保证默认键齐全，只覆盖测试关心的字段。 */
    private static String containerConfig(java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> customizer) {
        var node = new ObjectMapper().createObjectNode();
        node.put("type", "CONTENT");
        node.put("preLoopFtl", "");
        node.put("postLoopFtl", "");
        node.put("zeroContentFtl", "");
        node.putObject("displayTemplates");
        customizer.accept(node);
        return node.toString();
    }

    private static String visualLayout(String mode, String code, java.util.Map<String, String> fragmentOverrides) {
        var node = new ObjectMapper().createObjectNode();
        if (mode != null) node.put("mode", mode);
        if (code != null) node.put("code", code);
        node.putArray("rows");
        if (fragmentOverrides != null) {
            var overrides = node.putObject("fragmentOverrides");
            fragmentOverrides.forEach(overrides::put);
        }
        return node.toString();
    }

    private com.powercms.cms.model.entity.ContentTypeEntity contentType(UUID id, String code) {
        var type = new com.powercms.cms.model.entity.ContentTypeEntity();
        type.setId(id);
        type.setSiteId(siteId);
        type.setCode(code);
        type.setName(code);
        return type;
    }

    @Test
    void blocksDeleteWhenTemplateIsReferenced() {
        UUID templateId = UUID.randomUUID();
        SiteTemplateEntity template = new SiteTemplateEntity();
        template.setId(templateId);
        template.setSiteId(siteId);
        template.setName("Used");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template));
        when(compositionRepo.countByTemplateId(templateId)).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteTemplate(siteId, templateId, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仍被 2 个页面组合引用");
    }

    @Test
    void replaceAclAuditsChange() {
        UUID templateId = UUID.randomUUID();
        SiteTemplateEntity template = new SiteTemplateEntity();
        template.setId(templateId);
        template.setSiteId(siteId);
        template.setName("ACL");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template));
        when(aclRepo.findByTemplateIdOrderByRoleCodeAsc(templateId)).thenReturn(List.of());
        when(aclRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.replaceAcl(siteId, templateId,
            List.of(new VisualLayoutService.AclEntry("editor", true, true, true, false)), "admin");
        verify(aclRepo).save(any());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).write(any(), any(), any(), any(), any(), any(), detail.capture());
        assertThat(detail.getValue()).contains("after=");
    }


    @Test
    void normalizesFragmentOverridesAndRejectsOversize() {
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> { SiteTemplateEntity e=inv.getArgument(0); if(e.getId()==null)e.setId(UUID.randomUUID()); return e; });
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0)); when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        var info=service.saveTemplate(siteId,null,null,"Fragments",null,"{\"fragmentOverrides\":{\"header\":\"<header>${content.title!}</header>\",\"footer\":\"   \"}}","DRAFT","admin",null);
        assertThat(info.layoutJson()).contains("fragmentOverrides").contains("<header>").doesNotContain("\"footer\":\"   \"");
        String huge="x".repeat(256*1024+1);
        assertThatThrownBy(()->service.saveTemplate(siteId,null,null,"Huge",null,"{\"fragmentOverrides\":{\"header\":\""+huge+"\"}}","DRAFT","admin",null)).hasMessageContaining("256 KiB");
    }

    @Test
    void identifiesAnonymousByPrefixOnly() {
        assertThat(VisualLayoutService.isAnonymousName(VisualLayoutService.ANONYMOUS_PREFIX + "123")).isTrue();
        assertThat(VisualLayoutService.isAnonymousName("x_" + VisualLayoutService.ANONYMOUS_PREFIX + "123")).isFalse();
        assertThat(VisualLayoutService.isAnonymousName(null)).isFalse();
        assertThat(VisualLayoutService.isAnonymousName("Named")).isFalse();
    }

    @Test
    void templatesListExcludesAnonymousLayouts() {
        SiteTemplateEntity named = template(UUID.randomUUID(), siteId, "Home");
        SiteTemplateEntity anon = template(UUID.randomUUID(), siteId, VisualLayoutService.ANONYMOUS_PREFIX + "123");
        when(templateRepo.findBySiteIdOrderByNameAsc(siteId)).thenReturn(List.of(named, anon));
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(any())).thenReturn(List.of());

        var list = service.templates(siteId, null, null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("Home");
        assertThat(list.get(0).anonymous()).isFalse();
    }

    @Test
    void pageLayoutClonesNamedTemplateIntoAnonymousCopy() {
        UUID sourceId = UUID.randomUUID();
        SiteTemplateEntity source = template(sourceId, siteId, "Named");
        source.setThemeId(UUID.randomUUID());
        source.setStatus("PUBLISHED");
        SiteDropZoneEntity z1 = zone(UUID.randomUUID(), sourceId, UUID.randomUUID(), "main");
        SiteDropZoneEntity z2 = zone(UUID.randomUUID(), sourceId, UUID.randomUUID(), "sidebar");
        when(templateRepo.findById(sourceId)).thenReturn(Optional.of(source));
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> {
            SiteTemplateEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(any())).thenReturn(List.of(z1, z2));
        when(zoneRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(revisionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.savePageLayout(siteId, sourceId,
            "{\"mode\":\"VISUAL\",\"rows\":[{\"boxes\":[{\"width\":12,\"zoneKeys\":[\"sidebar\",\"main\"]}]}]}",
            "admin", "detach");

        assertThat(result.anonymous()).isTrue();
        assertThat(result.name()).startsWith(VisualLayoutService.ANONYMOUS_PREFIX);
        assertThat(result.id()).isNotEqualTo(sourceId);
        assertThat(result.zones()).extracting(VisualLayoutService.ZoneInfo::zoneKey)
            .containsExactlyInAnyOrder("main", "sidebar");
        assertThat(source.getName()).isEqualTo("Named");
        ArgumentCaptor<SiteDropZoneEntity> savedZone = ArgumentCaptor.forClass(SiteDropZoneEntity.class);
        verify(zoneRepo, org.mockito.Mockito.atLeastOnce()).save(savedZone.capture());
        assertThat(savedZone.getAllValues()).allMatch(z -> !sourceId.equals(z.getTemplateId()));
    }

    @Test
    void pageLayoutUpdatesAnonymousTemplateInPlace() {
        UUID anonId = UUID.randomUUID();
        SiteTemplateEntity anon = template(anonId, siteId, VisualLayoutService.ANONYMOUS_PREFIX + System.currentTimeMillis());
        anon.setStatus("DRAFT");
        when(templateRepo.findById(anonId)).thenReturn(Optional.of(anon));
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(zoneRepo.findByTemplateIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(revisionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.savePageLayout(siteId, anonId,
            "{\"mode\":\"VISUAL\",\"rows\":[{\"boxes\":[{\"width\":12}]}]}", "admin", null);

        assertThat(result.id()).isEqualTo(anonId);
        assertThat(result.name()).isEqualTo(anon.getName());
        assertThat(result.anonymous()).isTrue();
    }

    private PageVisualCompositionEntity composition(UUID pageId, UUID templateId, String zonesJson) {
        PageVisualCompositionEntity entity = new PageVisualCompositionEntity();
        entity.setPageId(pageId); entity.setTemplateId(templateId); entity.setZonesJson(zonesJson);
        return entity;
    }

    private SiteTemplateEntity template(UUID id, UUID ownerSiteId, String name) {
        SiteTemplateEntity entity = new SiteTemplateEntity();
        entity.setId(id); entity.setSiteId(ownerSiteId); entity.setName(name);
        return entity;
    }

    private SiteDropZoneEntity zone(UUID id, UUID templateId, UUID containerId, String key) {
        SiteDropZoneEntity entity = new SiteDropZoneEntity();
        entity.setId(id); entity.setTemplateId(templateId); entity.setContainerId(containerId); entity.setZoneKey(key);
        return entity;
    }

    private SiteContainerEntity container(UUID id) {
        SiteContainerEntity entity = new SiteContainerEntity();
        entity.setId(id);
        entity.setSiteId(siteId);
        entity.setName("C");
        return entity;
    }

    // ---------- regression: CODE-mode save-time sandbox validation ----------

    /** CODE referencing an unknown namespace (@powercms.zone) is rejected at save. */
    @Test
    void codeTemplateReferencingUnknownNamespaceIsRejectedAtSave() {
        String layout = "{\"mode\":\"CODE\",\"code\":\"<html><body><@powercms.zone key=\\\"main\\\"/></body></html>\"}";
        assertThatThrownBy(() -> service.saveTemplate(siteId, null, null, "bad", null, layout, "DRAFT", "tester", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("powercms");
    }

    /** FreeMarker syntax error (unclosed directive) is rejected at save. */
    @Test
    void codeTemplateWithSyntaxErrorIsRejectedAtSave() {
        String layout = "{\"mode\":\"CODE\",\"code\":\"<#list xs as x><p>hi</p>\"}";
        assertThatThrownBy(() -> service.saveTemplate(siteId, null, null, "bad-syntax", null, layout, "DRAFT", "tester", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("\u8bed\u6cd5\u9519\u8bef");
    }

    /** Healthy CODE with documented roots and bare data refs must NOT be blocked. */
    @Test
    void codeTemplateWithDocumentedRootsSaves() {
        stubTemplatePersist();
        String layout = "{\"mode\":\"CODE\",\"code\":\"<html><body><h1>${(page.title)!''}</h1>"
            + "<div>${contentModel.body_html}</div><@renderComponent \\\"hero\\\"/></body></html>\"}";
        var saved = service.saveTemplate(siteId, null, null, "good", null, layout, "DRAFT", "tester", null);
        assertThat(saved.name()).isEqualTo("good");
    }

    /** A typo in a top-level model root is rejected as an undefined reference. */
    @Test
    void codeTemplateWithTypoedTopLevelRootIsRejected() {
        String layout = "{\"mode\":\"CODE\",\"code\":\"${contntModel.body}\"}";
        assertThatThrownBy(() -> service.saveTemplate(siteId, null, null, "typo", null, layout, "DRAFT", "tester", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("contntModel");
    }
}