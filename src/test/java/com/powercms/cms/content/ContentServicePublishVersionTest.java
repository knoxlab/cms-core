package com.powercms.cms.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.compose.ClassComposeService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.ActorNameResolver;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.outbox.OutboxService;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-11-02：发布是否作为一次版本事件写入版本历史（产品确认方案 B——发布生成版本）。
 *
 * <p>修复前：发布只改 {@code content_item.status / published_version}，不写 {@code content_version}，
 * 版本历史里无法回答「线上跑的是哪一版、那一版发布了什么」。</p>
 *
 * <p>修复后：发布成功且「发布事件成立」时，以条目当前快照新增一条版本（{@code note=发布}、
 * {@code published=true}）并把 {@code published_version} 指向它；内容未变的重复发布不落版本。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentServicePublishVersionTest {

    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository ctRepo;
    @Mock FieldDefinitionRepository fieldRepo;
    @Mock ClassComposeService classCompose;
    @Mock AuditService audit;
    @Mock AiDirectPublishGate gate;
    @Mock OutboxService outbox;
    @Mock com.powercms.cms.identity.repo.UserRepository userRepo;

    private ContentService service;
    private final UUID site = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();

    private static final String FIELDS = "{\"summary_t\":\"发布时的正文\"}";

    @BeforeEach
    void setUp() {
        service = new ContentService(itemRepo, mock(CategoryRepository.class), versionRepo, ctRepo,
            fieldRepo, mock(SiteFolderRepository.class), classCompose, null,
            new ObjectMapper(), outbox, mock(ApplicationEventPublisher.class),
            mock(ObjectPermissionService.class), audit, gate,
            new ActorNameResolver(userRepo), null);
        // 非 PAGE 模型（objectType 非 PAGE）：发布检查只校验标题/版本/字段，不触碰可视化布局服务
        lenient().when(ctRepo.findById(typeId)).thenReturn(Optional.of(sharedType()));
        // 组合字段服务是纯改写逻辑，这里直通，避免 mock 返回 null 把 field_values 抹掉
        lenient().when(classCompose.applyOnSave(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        lenient().when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ContentTypeEntity sharedType() {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(typeId);
        type.setSiteId(site);
        type.setObjectType("SHARED");
        return type;
    }

    /** 条目 + 其最新版本行（条目表不存字段值，发布快照取最新版本行的 field_values）。 */
    private ContentItemEntity stubItem(String status, Integer currentVersion, Integer publishedVersion) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(itemId);
        item.setSiteId(site);
        item.setContentTypeId(typeId);
        item.setStatus(status);
        item.setCurrentVersion(currentVersion == null ? 0 : currentVersion);
        item.setPublishedVersion(publishedVersion);
        item.setTitle("发布回归标题");
        item.setRoutePath("/probe/publish");
        item.setTemplatePath("/t.ftl");
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        if (currentVersion != null && currentVersion > 0) {
            lenient().when(versionRepo.findByContentItemIdOrderByVersionDesc(itemId)).thenReturn(List.of(
                ContentVersionEntity.create(itemId, currentVersion, FIELDS, 1, "editor", "人工保存")
                    .withItemSnapshot(item.getTitle(), item.getRoutePath(), item.getTemplatePath())));
        }
        return item;
    }

    private FieldDefinitionEntity fieldDefinition(String code) {
        FieldDefinitionEntity f = new FieldDefinitionEntity();
        f.setCode(code);
        return f;
    }

    private ContentVersionEntity savedVersion() {        ArgumentCaptor<ContentVersionEntity> captor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(captor.capture());
        return captor.getValue();
    }

    private ContentItemEntity savedItem() {
        ArgumentCaptor<ContentItemEntity> captor = ArgumentCaptor.forClass(ContentItemEntity.class);
        verify(itemRepo, atLeastOnce()).save(captor.capture());
        List<ContentItemEntity> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    /** 首次发布：新增发布版本（note=发布、published=true），published_version 指向它。 */
    @Test
    void firstPublishCreatesPublishedVersionSnapshot() {
        ContentItemEntity item = stubItem(ContentItemEntity.STATUS_DRAFT, 3, null);

        service.updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, ContentService.PublishSource.USER);

        ContentVersionEntity v = savedVersion();
        assertEquals(4, v.getVersion(), "发布应把 currentVersion 推进到新版本");
        assertEquals("发布", v.getNote());
        assertTrue(v.isPublished(), "发布版本必须标记 published=true（版本历史发布节点）");
        assertEquals(FIELDS, v.getFieldValues(), "发布版本快照取发布时刻的内容");
        assertEquals("发布回归标题", v.getTitle());
        assertEquals("/probe/publish", v.getRoutePath());
        assertEquals("/t.ftl", v.getTemplatePath());
        assertNull(v.getRevertedFrom(), "发布版本不是恢复版本，不应写来源版本");

        ContentItemEntity after = savedItem();
        assertEquals(ContentItemEntity.STATUS_PUBLISHED, after.getStatus());
        assertEquals(4, after.getCurrentVersion());
        assertEquals(4, after.getPublishedVersion(), "published_version 应指向发布版本");
        assertEquals(item.getId(), after.getId());
    }

    /** 内容未变的重复发布：不重复落版本（避免版本历史被空发布刷屏）。 */
    @Test
    void republishWithoutContentChangeDoesNotCreateVersion() {
        ContentItemEntity item = stubItem(ContentItemEntity.STATUS_PUBLISHED, 4, 4);

        service.updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, ContentService.PublishSource.USER);

        verify(versionRepo, never()).save(any());
        assertEquals(4, item.getCurrentVersion(), "无变更的重复发布不应推进版本号");
        assertEquals(4, item.getPublishedVersion());
    }

    /** 发布后又有编辑（currentVersion 前进）：再次发布落新发布版本。 */
    @Test
    void publishAfterFurtherEditCreatesAnotherPublishedVersion() {
        ContentItemEntity item = stubItem(ContentItemEntity.STATUS_PUBLISHED, 5, 4);

        service.updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, ContentService.PublishSource.USER);

        ContentVersionEntity v = savedVersion();
        assertEquals(6, v.getVersion());
        assertTrue(v.isPublished());
        assertEquals(6, item.getPublishedVersion());
    }

    /** 审批链末步发布（WORKFLOW）与人工发布口径一致，同样落发布版本。 */
    @Test
    void workflowPublishAlsoRecordsPublishedVersion() {
        stubItem(ContentItemEntity.STATUS_APPROVED, 2, null);

        service.updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, ContentService.PublishSource.WORKFLOW);

        assertEquals(3, savedVersion().getVersion());
        assertTrue(savedItem().getPublishedVersion() == 3);
    }

    /**
     * 发布不做字段重校验：不调用类级组合 / 媒体引用校验。
     *
     * <p>否则「保存时合法、发布时因数据漂移而失败」会成为新的发布阻断点；发布前所需校验由
     * {@code publishChecklist} 覆盖。</p>
     */
    @Test
    void publishVersionDoesNotRevalidateFieldValues() {
        stubItem(ContentItemEntity.STATUS_DRAFT, 1, null);

        service.updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, ContentService.PublishSource.USER);

        verify(classCompose, never()).applyOnSave(any(), any(), any());
    }

    /** 发布版本在版本历史接口里带上 published 标记（前端「发布」标签数据源）。 */
    @Test
    void versionHistoryExposesPublishedFlag() {
        ContentVersionEntity published = ContentVersionEntity.create(itemId, 4, FIELDS, 1, "editor", "发布")
            .withItemSnapshot("标题", "/p", "/t.ftl")
            .withPublished(true);
        ContentVersionEntity manual = ContentVersionEntity.create(itemId, 3, FIELDS, 1, "editor", "人工保存")
            .withItemSnapshot("标题", "/p", "/t.ftl");
        when(versionRepo.findByContentItemIdOrderByVersionDesc(itemId))
            .thenReturn(List.of(published, manual));

        List<ContentService.VersionInfo> rows = service.versions(itemId);

        assertTrue(rows.get(0).published(), "发布版本行应带 published=true");
        assertEquals("发布", rows.get(0).note());
        assertFalse(rows.get(1).published(), "人工保存版本 published=false");
    }

    /** 普通保存 / AI 采纳 / 恢复产生的版本都不是发布版本（published=false）。 */
    @Test
    void ordinarySaveAndRevertAreNotPublishedVersions() {
        ContentItemEntity item = stubItem(ContentItemEntity.STATUS_DRAFT, 3, null);
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2)).thenReturn(Optional.of(
            ContentVersionEntity.create(itemId, 2, FIELDS, 1, "editor", "旧版本")
                .withItemSnapshot(item.getTitle(), item.getRoutePath(), item.getTemplatePath())));
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(typeId)).thenReturn(List.of(
            fieldDefinition("summary_t")));

        ContentService.VersionInfo manual = service.saveVersion(itemId, FIELDS, "人工修改",
            item.getTitle(), item.getRoutePath(), item.getTemplatePath(), "editor");
        ContentService.VersionInfo ai = service.saveAiVersion(itemId, FIELDS, "AI 建议采纳",
            item.getTitle(), item.getRoutePath(), item.getTemplatePath(), "editor", "deepseek-v4-flash", 1);
        stubItem(ContentItemEntity.STATUS_DRAFT, 3, null);
        ContentService.VersionInfo reverted = service.revertToVersion(itemId, 2, "editor");

        assertFalse(manual.published());
        assertFalse(ai.published());
        assertFalse(reverted.published(), "恢复版本只写 revertedFrom，不应被当成发布节点");
    }
}
