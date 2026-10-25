package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.model.AnalyticsComponentSeedService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.entity.SiteContainerEntity;
import com.powercms.cms.sitepackage.entity.SiteDropZoneEntity;
import com.powercms.cms.sitepackage.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 统计采集组件投放（FR-STAT-001 注入开关）：
 * 系统 COMPONENT 类型可投放受限容器；普通类型仍被 allowlist 拒绝。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsCompositionPlacementTest {

    @Mock PageVisualCompositionRepository compositions;
    @Mock ContentItemRepository items;
    @Mock ContentTypeRepository types;
    @Mock SiteTemplateRepository templates;
    @Mock SiteDropZoneRepository zones;
    @Mock SiteContainerRepository containers;
    @Mock SiteContainerStructureRepository containerStructures;
    @Mock AuditService audit;
    @Mock VisualLayoutService layouts;

    private PageCompositionService service;
    private final UUID siteId = UUID.randomUUID();
    private final UUID pageId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final UUID restrictedContainerId = UUID.randomUUID();
    private final UUID trackerTypeId = UUID.randomUUID();
    private final UUID otherTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PageCompositionService(compositions, items, types, templates, zones,
            containers, containerStructures, new ComponentNormalizer(new ObjectMapper(), types, null, null),
            new ObjectMapper(), audit, layouts);

        com.powercms.cms.content.entity.ContentItemEntity page = new com.powercms.cms.content.entity.ContentItemEntity();
        page.setId(pageId);
        page.setSiteId(siteId);
        page.setContentTypeId(UUID.randomUUID());
        when(items.findById(pageId)).thenReturn(Optional.of(page));
        ContentTypeEntity pageType = new ContentTypeEntity();
        pageType.setObjectType("PAGE");
        when(types.findById(page.getContentTypeId())).thenReturn(Optional.of(pageType));

        com.powercms.cms.sitepackage.entity.SiteTemplateEntity template =
            new com.powercms.cms.sitepackage.entity.SiteTemplateEntity();
        template.setId(templateId);
        template.setSiteId(siteId);
        when(templates.findById(templateId)).thenReturn(Optional.of(template));

        SiteDropZoneEntity zone = new SiteDropZoneEntity();
        zone.setZoneKey("footer");
        zone.setContainerId(restrictedContainerId);
        when(zones.findByTemplateIdOrderBySortOrderAsc(templateId)).thenReturn(List.of(zone));

        SiteContainerEntity container = new SiteContainerEntity();
        container.setId(restrictedContainerId);
        // allowlist 里只放一个与本测试无关的第三方类型
        container.setAllowedComponentTypes("[\"" + UUID.randomUUID() + "\"]");
        when(containers.findById(restrictedContainerId)).thenReturn(Optional.of(container));
        when(containerStructures.findByContainerIdOrderBySortOrderAsc(restrictedContainerId)).thenReturn(List.of());

        // 系统 COMPONENT 类型：统计采集
        ContentTypeEntity trackerType = new ContentTypeEntity();
        trackerType.setId(trackerTypeId);
        trackerType.setObjectType("COMPONENT");
        trackerType.setCode(AnalyticsComponentSeedService.ANALYTICS_TRACKER);
        trackerType.setSiteId(siteId);
        trackerType.setSystem(true);
        lenient().when(types.findById(trackerTypeId)).thenReturn(Optional.of(trackerType));
        when(types.findBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "COMPONENT")).thenReturn(List.of(trackerType));

        // 普通组件类型：不在 allowlist 内、非系统
        ContentTypeEntity otherType = new ContentTypeEntity();
        otherType.setId(otherTypeId);
        otherType.setObjectType("COMPONENT");
        otherType.setCode("custom.widget");
        otherType.setSiteId(siteId);
        lenient().when(types.findById(otherTypeId)).thenReturn(Optional.of(otherType));

        when(compositions.findByPageId(pageId)).thenReturn(Optional.empty());
        when(compositions.save(any())).thenAnswer(inv -> {
            PageVisualCompositionEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                // 实体 id 由 @PrePersist 生成；Mockito 下手动赋值以便审计写库
                java.lang.reflect.Field idField = PageVisualCompositionEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(e, UUID.randomUUID());
            }
            return e;
        });
    }

    private ComponentInstance embedded(UUID typeId) {
        return new ComponentInstance(UUID.randomUUID().toString(), ComponentInstance.Kind.EMBEDDED,
            null, null, typeId, "t", Map.of(), null, null, Map.of(), 0);
    }

    @Test
    void systemTrackerPassesRestrictedContainerAllowlist() {
        var info = service.save(siteId, pageId, templateId, Map.of("footer", List.of(embedded(trackerTypeId))), 0, "tester");
        assertThat(info.zones().get("footer")).hasSize(1);
    }

    @Test
    void ordinaryComponentStillRejectedByAllowlist() {
        assertThatThrownBy(() -> service.save(siteId, pageId, templateId,
            Map.of("footer", List.of(embedded(otherTypeId))), 0, "tester"))
            .hasMessageContaining("不在投放区允许范围内");
    }

    @Test
    void systemLookupIsCachedPerSave() {
        service.save(siteId, pageId, templateId,
            Map.of("footer", List.of(embedded(trackerTypeId), embedded(trackerTypeId))), 0, "tester");
        org.mockito.Mockito.verify(types, org.mockito.Mockito.times(1))
            .findBySiteIdAndObjectTypeOrderByCodeAsc(eq(siteId), eq("COMPONENT"));
    }
}
