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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-AI-GOV-003：AI 修改作为独立版本写入版本历史，版本元数据标记 AI 参与；
 * 普通人工保存版本不带 AI 标记；版本历史/详情展示 AI 版本信息。
 */
@ExtendWith(MockitoExtension.class)
class ContentServiceAiVersionTest {

    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository ctRepo;
    @Mock AuditService audit;
    @Mock AiDirectPublishGate gate;

    private ContentService service;
    private final UUID site = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentService(itemRepo, mock(CategoryRepository.class), versionRepo, ctRepo,
            mock(FieldDefinitionRepository.class), mock(SiteFolderRepository.class),
            mock(ClassComposeService.class), null, new ObjectMapper(),
            mock(OutboxService.class), mock(ApplicationEventPublisher.class),
            mock(ObjectPermissionService.class), audit, gate,
            ActorNameResolver.passThrough(), null);
    }

    private void stubDraftItem() {
        stubDraftItem(false, null, null);
    }

    private void stubDraftItem(boolean aiGenerated, String aiModel, Integer aiPromptVersion) {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(typeId);
        type.setSiteId(site);
        type.setObjectType("PAGE");
        when(ctRepo.findById(typeId)).thenReturn(Optional.of(type));

        ContentItemEntity item = new ContentItemEntity();
        item.setId(itemId);
        item.setSiteId(site);
        item.setContentTypeId(typeId);
        item.setStatus(ContentItemEntity.STATUS_DRAFT);
        item.setTitle("标题");
        item.setRoutePath("/p");
        item.setCurrentVersion(3);
        item.setAiGenerated(aiGenerated);
        item.setAiModel(aiModel);
        item.setAiPromptVersion(aiPromptVersion);
        if (aiGenerated) item.setAiGeneratedAt(java.time.Instant.now());
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
    }

    @Test
    void saveAiVersionMarksVersionAndItemAndAudits() {
        stubDraftItem();
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentService.VersionInfo result = service.saveAiVersion(itemId,
            "{\"body\":\"AI 内容\"}", "AI 采纳", null, "/p", "/t.ftl", "editor",
            "gpt-4o", 7);

        assertEquals(4, result.version());
        assertTrue(result.aiGenerated());
        assertEquals("gpt-4o", result.aiModel());
        assertEquals(7, result.aiPromptVersion());
        assertNotNull(result.aiGeneratedAt());

        ArgumentCaptor<ContentVersionEntity> captor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(captor.capture());
        ContentVersionEntity saved = captor.getValue();
        assertTrue(saved.isAiGenerated());
        assertEquals("gpt-4o", saved.getAiModel());
        assertEquals(7, saved.getAiPromptVersion());
        assertNotNull(saved.getAiGeneratedAt());

        // 内容条目 AI 标识同步写入（最后一次保存携带模型/提示词版本）+ 审计
        ArgumentCaptor<ContentItemEntity> itemCaptor = ArgumentCaptor.forClass(ContentItemEntity.class);
        verify(itemRepo, org.mockito.Mockito.atLeastOnce()).save(itemCaptor.capture());
        ContentItemEntity savedItem = itemCaptor.getAllValues().get(itemCaptor.getAllValues().size() - 1);
        assertTrue(savedItem.isAiGenerated());
        assertEquals("gpt-4o", savedItem.getAiModel());
        verify(audit).write(eq("editor"), eq("ai.content.versioned"), eq("ContentItem"),
            eq(itemId.toString()), eq(site), eq("success"), any());
    }

    @Test
    void manualSaveVersionKeepsVersionAiMarkerFalse() {
        stubDraftItem();
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentService.VersionInfo result = service.saveVersion(itemId,
            "{\"body\":\"人工编辑\"}", "人工修改", null, "/p", "/t.ftl", "editor");

        assertFalse(result.aiGenerated());
        ArgumentCaptor<ContentVersionEntity> captor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(captor.capture());
        assertFalse(captor.getValue().isAiGenerated());
    }

    @Test
    void versionHistoryExposesAiMarkers() {
        ContentVersionEntity aiVersion = ContentVersionEntity.create(itemId, 4, "{\"body\":\"AI\"}",
            1, "editor", "AI 采纳", "deepseek-r1", 3);
        when(versionRepo.findByContentItemIdOrderByVersionDesc(itemId)).thenReturn(java.util.List.of(aiVersion));

        var versions = service.versions(itemId);
        assertEquals(1, versions.size());
        ContentService.VersionInfo info = versions.get(0);
        assertTrue(info.aiGenerated());
        assertEquals("deepseek-r1", info.aiModel());
        assertEquals(3, info.aiPromptVersion());
    }

    /** GOV-007 写保护：普通人工编辑生成的版本不带 AI 标记，且不得清除条目的 AI 标识。 */
    @Test
    void humanEditAfterAiKeepsItemMarker() {
        stubDraftItem(true, "deepseek-r1", 3);
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveVersion(itemId, "{\"body\":\"人工接手修改\"}", "人工修改",
            null, "/p", "/t.ftl", "editor");

        // 人工版本本身无 AI 标记
        ArgumentCaptor<ContentVersionEntity> versionCaptor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(versionCaptor.capture());
        assertFalse(versionCaptor.getValue().isAiGenerated());
        // 条目 AI 标识仍保留（不可被普通编辑流程移除）
        ArgumentCaptor<ContentItemEntity> itemCaptor = ArgumentCaptor.forClass(ContentItemEntity.class);
        verify(itemRepo, org.mockito.Mockito.atLeastOnce()).save(itemCaptor.capture());
        ContentItemEntity saved = itemCaptor.getAllValues().get(itemCaptor.getAllValues().size() - 1);
        assertTrue(saved.isAiGenerated());
        assertEquals("deepseek-r1", saved.getAiModel());
    }

    /** GOV-007 隐式标识：内容详情 API 透出 aiGenerated/aiModel/aiPromptVersion。 */
    @Test
    void detailCarriesImplicitAiMarker() {
        stubDraftItem(true, "gpt-4o", 7);

        ContentService.ContentInfo info = service.get(itemId);

        assertTrue(info.aiGenerated());
        assertEquals("gpt-4o", info.aiModel());
        assertEquals(7, info.aiPromptVersion());
        assertNotNull(info.aiGeneratedAt());
    }
}
