package com.powercms.cms.content.relation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.ContentService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-AI-SRC-003 跨源关系：模板 CRUD 校验 / 自动挂链（锚点匹配+去重）/ 手动导航分组。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossSourceRelationServiceTest {

    @Mock private RelationTemplateRepository templates;
    @Mock private RelationTemplateLinkLogRepository linkLogs;
    @Mock private ContentRelationRepository relationRepo;
    @Mock private ContentRelationService relationService;
    @Mock private ContentItemRepository items;
    @Mock private ContentService contentService;
    @Mock private AuditService audit;

    private final ObjectMapper mapper = new ObjectMapper();
    private CrossSourceRelationService service;

    private final UUID siteId = UUID.randomUUID();
    private final UUID dsId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID anchorId = UUID.randomUUID();
    private final UUID anchorTypeId = UUID.randomUUID();
    private final UUID recordTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CrossSourceRelationService(templates, linkLogs, relationRepo,
            relationService, items, contentService, audit, mapper);
    }

    private RelationTemplateEntity template() {
        RelationTemplateEntity t = new RelationTemplateEntity();
        t.setId(UUID.randomUUID());
        t.setSiteId(siteId);
        t.setName("产品参数挂链");
        t.setDatasourceId(dsId);
        t.setRelationName("product_params");
        t.setAnchorField("productCode");
        t.setRecordField("product_ref");
        t.setStatus(RelationTemplateEntity.STATUS_ACTIVE);
        return t;
    }

    private ContentRelationEntity definition() {
        ContentRelationEntity d = new ContentRelationEntity();
        d.setId(UUID.randomUUID());
        d.setSiteId(siteId);
        d.setRelationName("product_params");
        d.setCardinality(ContentRelationEntity.MANY_TO_MANY);
        d.setSourceTypeId(anchorTypeId);
        d.setTargetTypeId(recordTypeId);
        return d;
    }

    private ContentItemEntity item(UUID id, UUID typeId, String sourceDomain) {
        ContentItemEntity i = new ContentItemEntity();
        i.setId(id);
        i.setSiteId(siteId);
        i.setContentTypeId(typeId);
        i.setTitle("t-" + id);
        i.setSourceDomain(sourceDomain);
        return i;
    }

    @Test
    void autoLinkMatchesAnchorAndRegistersRelation() {
        // 锚点产品（CMS，productCode=ABC-100）；同步记录（PIM）携带 product_ref=ABC-100
        RelationTemplateEntity t = template();
        when(templates.findByDatasourceIdAndStatus(dsId, RelationTemplateEntity.STATUS_ACTIVE))
            .thenReturn(List.of(t));
        ContentItemEntity record = item(recordId, recordTypeId, "PIM:pim-1");
        when(items.findById(recordId)).thenReturn(Optional.of(record));
        ContentRelationEntity def = definition();
        when(relationRepo.findBySiteIdAndRelationName(siteId, "product_params"))
            .thenReturn(Optional.of(def));
        ContentItemEntity anchor = item(anchorId, anchorTypeId, "CMS");
        when(items.findBySiteId(eq(siteId), any())).thenAnswer(inv -> {
            int page = ((PageRequest) inv.getArgument(1)).getPageNumber();
            return page == 0 ? new PageImpl<>(List.of(anchor)) : new PageImpl<>(List.of());
        });
        lenient().when(contentService.versions(anchorId)).thenReturn(List.of(
            new ContentService.VersionInfo(UUID.randomUUID(), 1,
                "{\"productCode\":\"ABC-100\"}", 1, null, "x", Instant.now(),
                false, null, null, null, null, null, null)));
        when(linkLogs.existsByTemplateIdAndRecordContentIdAndAnchorContentId(
            t.getId(), recordId, anchorId)).thenReturn(false);

        service.autoLink(dsId, recordId, "{\"product_ref\":\"ABC-100\",\"price\":\"99\"}");

        verify(relationService).addRelation(recordId, "product_params", anchorId, "datasource-sync");
        verify(linkLogs).save(any(RelationTemplateLinkLogEntity.class));
    }

    @Test
    void autoLinkSkipsWhenAlreadyLinked() {
        // 去重：同一模板对同一对内容只挂一次
        RelationTemplateEntity t = template();
        when(templates.findByDatasourceIdAndStatus(dsId, RelationTemplateEntity.STATUS_ACTIVE))
            .thenReturn(List.of(t));
        ContentItemEntity record = item(recordId, recordTypeId, "PIM:pim-1");
        when(items.findById(recordId)).thenReturn(Optional.of(record));
        when(relationRepo.findBySiteIdAndRelationName(siteId, "product_params"))
            .thenReturn(Optional.of(definition()));
        ContentItemEntity anchor = item(anchorId, anchorTypeId, "CMS");
        when(items.findBySiteId(eq(siteId), any())).thenAnswer(inv ->
            new PageImpl<>(List.of(anchor)));
        when(contentService.versions(anchorId)).thenReturn(List.of(
            new ContentService.VersionInfo(UUID.randomUUID(), 1,
                "{\"productCode\":\"ABC-100\"}", 1, null, "x", Instant.now(),
                false, null, null, null, null, null, null)));
        when(linkLogs.existsByTemplateIdAndRecordContentIdAndAnchorContentId(
            t.getId(), recordId, anchorId)).thenReturn(true);

        service.autoLink(dsId, recordId, "{\"product_ref\":\"ABC-100\"}");

        verify(relationService, never()).addRelation(any(), any(), any(), any());
        verify(linkLogs, never()).save(any());
    }

    @Test
    void autoLinkSkipsWhenRecordMissesAnchorValue() {
        // 记录未携带锚点值：跳过且不扫描
        RelationTemplateEntity t = template();
        when(templates.findByDatasourceIdAndStatus(dsId, RelationTemplateEntity.STATUS_ACTIVE))
            .thenReturn(List.of(t));
        ContentItemEntity record = item(recordId, recordTypeId, "PIM:pim-1");
        when(items.findById(recordId)).thenReturn(Optional.of(record));

        service.autoLink(dsId, recordId, "{\"price\":\"99\"}");

        verify(relationRepo, never()).findBySiteIdAndRelationName(any(), any());
        verify(relationService, never()).addRelation(any(), any(), any(), any());
    }

    @Test
    void autoLinkIsolatedPerTemplate() {
        // 单模板失败（关系定义缺失）不影响其他模板（逐模板捕获 + 失败审计）
        RelationTemplateEntity broken = template();
        broken.setRelationName("missing_relation");
        RelationTemplateEntity ok = template();
        ok.setId(UUID.randomUUID());
        ok.setName("另一模板");
        ok.setRelationName("product_manuals");
        ContentRelationEntity def = definition();
        def.setRelationName("product_manuals");
        when(templates.findByDatasourceIdAndStatus(dsId, RelationTemplateEntity.STATUS_ACTIVE))
            .thenReturn(List.of(broken, ok));
        ContentItemEntity record = item(recordId, recordTypeId, "PIM:pim-1");
        when(items.findById(recordId)).thenReturn(Optional.of(record));
        when(relationRepo.findBySiteIdAndRelationName(siteId, "missing_relation"))
            .thenReturn(Optional.empty());
        when(relationRepo.findBySiteIdAndRelationName(siteId, "product_manuals"))
            .thenReturn(Optional.of(def));
        ContentItemEntity anchor = item(anchorId, anchorTypeId, "CMS");
        when(items.findBySiteId(eq(siteId), any())).thenAnswer(inv ->
            new PageImpl<>(List.of(anchor)));
        when(contentService.versions(anchorId)).thenReturn(List.of(
            new ContentService.VersionInfo(UUID.randomUUID(), 1,
                "{\"productCode\":\"ABC-100\"}", 1, null, "x", Instant.now(),
                false, null, null, null, null, null, null)));

        service.autoLink(dsId, recordId, "{\"product_ref\":\"ABC-100\"}");

        verify(relationService).addRelation(recordId, "product_manuals", anchorId, "datasource-sync");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).write(any(), action.capture(),
            any(), any(), any(), any(), any());
        assertThat(action.getAllValues()).contains("relation.template.link_failed");
    }

    @Test
    void createTemplateValidatesRelationExists() {
        when(relationRepo.findBySiteIdAndRelationName(siteId, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTemplate(siteId, "模板", dsId, "nope",
            "productCode", "product_ref", "admin"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void createTemplateRejectsDuplicateName() {
        when(templates.existsBySiteIdAndName(siteId, "重名")).thenReturn(true);

        assertThatThrownBy(() -> service.createTemplate(siteId, "重名", dsId, "r",
            "productCode", "product_ref", "admin"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void navigationEnrichesItemsWithSourceDomain() {
        // 跨源导航：关联条目带 sourceDomain 与 sourceType（DAM/PIM 分组标注）
        ContentItemEntity product = item(recordId, anchorTypeId, "CMS");
        when(items.findById(recordId)).thenReturn(Optional.of(product));
        ContentItemEntity manual = item(anchorId, anchorTypeId, "DAM:knox-1");
        when(items.findById(anchorId)).thenReturn(Optional.of(manual));
        when(relationService.relations(recordId)).thenReturn(List.of(new java.util.LinkedHashMap<>(
            java.util.Map.of("relationName", "product_manuals",
                "cardinality", "MANY_TO_MANY",
                "outgoing", List.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                    "id", anchorId, "title", "说明书.pdf"))),
                "incoming", List.of()))));

        var result = service.navigation(recordId);

        assertThat(result.get("sourceDomain")).isEqualTo("CMS");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> groups =
            (List<java.util.Map<String, Object>>) result.get("relations");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> outgoing =
            (List<java.util.Map<String, Object>>) groups.get(0).get("outgoing");
        assertThat(outgoing.get(0).get("sourceDomain")).isEqualTo("DAM:knox-1");
        assertThat(outgoing.get(0).get("sourceType")).isEqualTo("DAM");
    }
}
