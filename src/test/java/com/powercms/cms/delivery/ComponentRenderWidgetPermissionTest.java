package com.powercms.cms.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.delivery.controller.SiteScopedContentQueryService;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.sitepackage.ComponentInstance;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.repo.SharedComponentRepository;
import com.powercms.cms.widget.entity.WidgetDefinitionEntity;
import com.powercms.cms.widget.repo.WidgetDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-DELIVERY-PERM-001 §3.5：widget 内容查询（query/byRoute）匿名过滤测试。
 * 匿名交付下剔除受限行；预览/Release 等非匿名渲染保留全部查询结果。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComponentRenderWidgetPermissionTest {

    @Mock SharedComponentRepository shared;
    @Mock PageCompositionService compositions;
    @Mock ContentItemRepository items;
    @Mock TemplateService templates;
    @Mock FreeMarkerSandbox sandbox;
    @Mock WidgetDefinitionRepository widgets;
    @Mock SiteScopedContentQueryService content;
    @Mock ObjectPermissionService objectPermissions;

    private ComponentRenderService service;
    private final UUID siteId = UUID.randomUUID();
    private final UUID pageId = UUID.randomUUID();
    private final UUID publicId = UUID.randomUUID();
    private final UUID restrictedId = UUID.randomUUID();
    private final UUID widgetId = UUID.randomUUID();
    private final UUID structureTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ComponentRenderService(shared, compositions, items, templates, sandbox,
            widgets, new ObjectMapper(), content, objectPermissions,
            new com.powercms.cms.identity.permission.ViewerAccessService(objectPermissions,
                org.mockito.Mockito.mock(com.powercms.cms.identity.repo.UserRepository.class)));

        WidgetDefinitionEntity widget = new WidgetDefinitionEntity();
        widget.setSiteId(siteId);
        widget.setStatus("PUBLISHED");
        widget.setControllerDefinition("""
            {"view":"/templates/list.ftl","templateModel":{},
             "queries":[{"as":"items","op":"query","limit":10}]}""");
        lenient().when(widgets.findById(widgetId)).thenReturn(Optional.of(widget));

        TemplateService.ProjInfo project = new TemplateService.ProjInfo(
            UUID.randomUUID(), siteId, "main", "FTL", null, 1, java.time.Instant.now());
        lenient().when(templates.listProjects(siteId)).thenReturn(List.of(project));
        lenient().when(templates.readFileContent(project.id(), "templates/list.ftl"))
            .thenReturn("<#list items as i>${i.title}</#list>");

        // 查询返回：一条公开 + 一条受限
        lenient().when(content.query(eq(siteId), eq("PUBLISHED"), anyInt()))
            .thenReturn(List.of(row(publicId, "公开"), row(restrictedId, "受限")));
        lenient().when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, restrictedId, ObjectType.VIEW)).thenReturn(false);
        lenient().when(objectPermissions.hasAnonymousRight(
            ObjectType.CONTENT, publicId, ObjectType.VIEW)).thenReturn(true);

        lenient().when(sandbox.render(anyString(), anyString(), any(), anyBoolean()))
            .thenReturn("<ul></ul>");
    }

    private Map<String, Object> row(UUID id, String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("title", title);
        return m;
    }

    private ComponentInstance widgetInstance() {
        return new ComponentInstance("w1", ComponentInstance.Kind.WIDGET, null, null,
            structureTypeId, "列表", Map.of(), null, null, Map.of(), 0,
            widgetId, Map.of(), null, null);
    }

    private List<?> renderedItems(boolean anonymousDelivery) {
        ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
        service.renderInstance(siteId, pageId, widgetInstance(), Map.of(), false,
            new HashSet<>(), 0, anonymousDelivery);
        verify(sandbox).render(anyString(), anyString(), model.capture(), anyBoolean());
        Object tm = model.getValue().get("templateModel");
        return tm instanceof Map<?, ?> mm && mm.get("items") instanceof List<?> list
            ? list : List.of();
    }

    @Test
    void anonymousDeliveryRemovesRestrictedRowsFromWidgetQuery() {
        List<?> items = renderedItems(true);

        assertThat(items).hasSize(1);
        Map<?, ?> only = (Map<?, ?>) items.get(0);
        assertThat(only.get("id")).isEqualTo(publicId.toString());
    }

    @Test
    void nonAnonymousDeliveryKeepsAllWidgetQueryRows() {
        List<?> items = renderedItems(false);

        assertThat(items).hasSize(2);
    }

    @Test
    void anonymousDeliveryKeepsAllowedRowEvenIfItAppearsAfterRestricted() {
        // 顺序：受限在前，公开在后
        lenient().when(content.query(eq(siteId), eq("PUBLISHED"), anyInt()))
            .thenReturn(List.of(row(restrictedId, "受限"), row(publicId, "公开")));

        List<?> items = renderedItems(true);

        assertThat(items).hasSize(1);
        Map<?, ?> only = (Map<?, ?>) items.get(0);
        assertThat(only.get("id")).isEqualTo(publicId.toString());
    }
}
