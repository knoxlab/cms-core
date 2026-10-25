package com.powercms.cms.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型版本迁移执行器（FR-CM-CT-015 扩展）：重绑 + 字段值迁移 + 迁移快照。
 */
@ExtendWith(MockitoExtension.class)
class ContentTypeMigrationServiceTest {

    @Mock private ContentItemRepository itemRepo;
    @Mock private ContentVersionRepository versionRepo;
    @Mock private FieldDefinitionRepository fieldRepo;

    private final ObjectMapper om = new ObjectMapper();
    private ContentTypeMigrationService service;
    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentTypeMigrationService(itemRepo, versionRepo, fieldRepo, om);
    }

    @Test
    void migratesRenamesDefaultsAndDrop() throws Exception {
        ContentTypeEntity newType = type(2);
        FieldDefinitionEntity added = field(newType.getId(), "summary", "简要介绍");
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(newType.getId())).thenReturn(List.of(added));

        ContentItemEntity item = item();
        ContentVersionEntity latest = ContentVersionEntity.create(item.getId(), 3,
            om.writeValueAsString(om.createObjectNode().put("body", "正文").put("obsolete", "x")),
            1, "editor", null);
        when(itemRepo.findByContentTypeId(fromTypeId())).thenReturn(List.of(item));
        when(versionRepo.findByContentItemIdOrderByVersionDesc(item.getId())).thenReturn(List.of(latest));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var rules = om.readTree("""
            {"renames": {"body": "content"}, "defaults": {"summary": "N/A"}, "drop": ["obsolete"]}
            """);
        var result = service.migrate(siteId, fromTypeId(), newType, rules);

        assertThat(result.itemsRebound()).isEqualTo(1);
        assertThat(result.versionsWritten()).isEqualTo(1);
        assertThat(item.getContentTypeId()).isEqualTo(newType.getId());
        assertThat(item.getCurrentVersion()).isEqualTo(4);
        // 已发布内容同步指向迁移快照
        assertThat(item.getPublishedVersion()).isEqualTo(4);

        ArgumentCaptor<ContentVersionEntity> snapshot = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(snapshot.capture());
        var values = om.readTree(snapshot.getValue().getFieldValues());
        // rename: body -> content
        assertThat(values.path("content").asText()).isEqualTo("正文");
        // drop: obsolete 移除
        assertThat(values.has("obsolete")).isFalse();
        // defaults: summary 回填
        assertThat(values.path("summary").asText()).isEqualTo("N/A");
        // 快照记录新模型版本
        assertThat(snapshot.getValue().getModelVersion()).isEqualTo(2);
        assertThat(snapshot.getValue().getVersion()).isEqualTo(4);
    }

    @Test
    void skipsMigrationInSkipModeButStillRebinds() {
        ContentTypeEntity newType = type(2);
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(newType.getId())).thenReturn(List.of());
        ContentItemEntity item = item();
        when(itemRepo.findByContentTypeId(fromTypeId())).thenReturn(List.of(item));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.migrate(siteId, fromTypeId(), newType, om.createObjectNode().put("mode", "SKIP"));

        assertThat(result.itemsRebound()).isEqualTo(1);
        assertThat(result.itemsMigrated()).isEqualTo(0);
        assertThat(item.getContentTypeId()).isEqualTo(newType.getId());
        verify(versionRepo, times(0)).save(any());
    }

    @Test
    void defaultModeBackfillsFieldDefaultValueWithoutRules() throws Exception {
        ContentTypeEntity newType = type(3);
        FieldDefinitionEntity added = field(newType.getId(), "cover_ready", null);
        added.setDefaultValue("false");
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(newType.getId())).thenReturn(List.of(added));

        ContentItemEntity item = item();
        ContentVersionEntity latest = ContentVersionEntity.create(item.getId(), 1,
            om.writeValueAsString(om.createObjectNode().put("title", "T")), 1, "editor", null);
        when(itemRepo.findByContentTypeId(fromTypeId())).thenReturn(List.of(item));
        when(versionRepo.findByContentItemIdOrderByVersionDesc(item.getId())).thenReturn(List.of(latest));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.migrate(siteId, fromTypeId(), newType, null);

        ArgumentCaptor<ContentVersionEntity> snapshot = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(snapshot.capture());
        var values = om.readTree(snapshot.getValue().getFieldValues());
        assertThat(values.path("title").asText()).isEqualTo("T");
        assertThat(values.path("cover_ready").asText()).isEqualTo("false");
    }

    @Test
    void rejectsInvalidMode() {
        ContentTypeEntity newType = type(2);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
            .isThrownBy(() -> service.migrate(siteId, fromTypeId(), newType,
                om.createObjectNode().put("mode", "DELETE_ALL")))
            .withMessageContaining("MIGRATE/SKIP");
    }

    @Test
    void crossSiteItemsAreIgnored() {
        ContentTypeEntity newType = type(2);
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(newType.getId())).thenReturn(List.of());
        ContentItemEntity foreign = item();
        foreign.setSiteId(UUID.randomUUID());
        when(itemRepo.findByContentTypeId(fromTypeId())).thenReturn(List.of(foreign));

        var result = service.migrate(siteId, fromTypeId(), newType, null);

        assertThat(result.itemsRebound()).isEqualTo(0);
        assertThat(foreign.getContentTypeId()).isEqualTo(fromTypeId());
    }

    private final UUID oldId = UUID.randomUUID();

    private UUID fromTypeId() {
        return oldId;
    }

    private ContentTypeEntity type(int version) {
        ContentTypeEntity ct = new ContentTypeEntity();
        ct.setId(UUID.randomUUID());
        ct.setSiteId(siteId);
        ct.setCode("news");
        ct.setName("news");
        ct.setVersion(version);
        ct.setStatus(ContentTypeEntity.STATUS_PUBLISHED);
        return ct;
    }

    private FieldDefinitionEntity field(UUID typeId, String code, String name) {
        FieldDefinitionEntity f = new FieldDefinitionEntity();
        f.setId(UUID.randomUUID());
        f.setContentTypeId(typeId);
        f.setCode(code);
        f.setName(name == null ? code : name);
        f.setFieldType("STRING");
        return f;
    }

    private ContentItemEntity item() {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(UUID.randomUUID());
        item.setSiteId(siteId);
        item.setContentTypeId(oldId);
        item.setTitle("T");
        item.setStatus(ContentItemEntity.STATUS_PUBLISHED);
        item.setCurrentVersion(3);
        item.setPublishedVersion(3);
        return item;
    }
}
