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
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-CV-VER-004/007/012：恢复到指定版本——「恢复创建新版本」语义、快照还原、兼容性校验与审计。
 *
 * <p>对应域 11 缺陷 `DEF-11-21`（版本历史弹窗缺少查看/对比/恢复能力）的服务端部分。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentServiceRevertVersionTest {

    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository ctRepo;
    @Mock FieldDefinitionRepository fieldRepo;
    @Mock ClassComposeService classCompose;
    @Mock AuditService audit;
    @Mock AiDirectPublishGate gate;
    @Mock com.powercms.cms.identity.repo.UserRepository userRepo;

    private ContentService service;
    private final UUID site = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentService(itemRepo, mock(CategoryRepository.class), versionRepo, ctRepo,
            fieldRepo, mock(SiteFolderRepository.class), classCompose, null,
            new ObjectMapper(), mock(OutboxService.class), mock(ApplicationEventPublisher.class),
            mock(ObjectPermissionService.class), audit, gate,
            new ActorNameResolver(userRepo), null);
        // 组合字段服务是纯改写逻辑，这里直通，避免 mock 返回 null 把 field_values 抹掉。
        // 用 lenient：部分用例（版本不存在 / 兼容性校验失败）根本走不到组合这一步。
        lenient().when(classCompose.applyOnSave(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
    }

    /** PAGE 型草稿内容，当前 v{currentVersion}、路径 /p、模板 /t.ftl。 */
    private void stubPageItem(int currentVersion) {
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
        item.setTitle("当前标题");
        item.setRoutePath("/p");
        item.setTemplatePath("/t.ftl");
        item.setCurrentVersion(currentVersion);
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
    }

    /** 只需内容条目（版本不存在、模型未定义等提前失败路径），避免出现无用 stub。 */
    private void stubItemOnly(int currentVersion) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(itemId);
        item.setSiteId(site);
        item.setContentTypeId(typeId);
        item.setStatus(ContentItemEntity.STATUS_DRAFT);
        item.setTitle("当前标题");
        item.setCurrentVersion(currentVersion);
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
    }

    private void stubModelFields(String... codes) {
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(typeId)).thenReturn(
            java.util.Arrays.stream(codes).map(code -> {
                FieldDefinitionEntity f = new FieldDefinitionEntity();
                f.setCode(code);
                return f;
            }).toList());
    }

    private ContentVersionEntity snapshotVersion(int version, String fields, String title,
                                                String routePath, String templatePath) {
        return ContentVersionEntity.create(itemId, version, fields, 1, "editor", "旧备注")
            .withItemSnapshot(title, routePath, templatePath);
    }

    @Test
    void revertCreatesNewVersionFromTargetSnapshotInsteadOfOverwritingHistory() {
        stubPageItem(3);
        stubModelFields("body_t", "summary_t");
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2)).thenReturn(Optional.of(
            snapshotVersion(2, "{\"body_t\":\"第 2 版正文\"}", "第 2 版标题", "/old", "/old.ftl")));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentService.VersionInfo result = service.revertToVersion(itemId, 2, "admin");

        // 恢复到 v2 产生的是 v4（不覆盖 v2/v3 历史）
        assertEquals(4, result.version());
        assertEquals("恢复到 v2", result.note());
        // 模型字段、标题、路径、模板均按目标版本快照还原
        assertEquals("{\"body_t\":\"第 2 版正文\"}", result.fieldValues());
        assertEquals("第 2 版标题", result.title());
        assertEquals("/old", result.routePath());
        assertEquals("/old.ftl", result.templatePath());

        ArgumentCaptor<ContentVersionEntity> captor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(captor.capture());
        assertTrue(captor.getValue().getFieldValues().contains("第 2 版正文"));
        // 恢复版本自身也带快照，可再次回退（FR-CV-VER-004「可再次回退」）
        assertEquals("/old", captor.getValue().getRoutePath());

        ArgumentCaptor<ContentItemEntity> itemCaptor = ArgumentCaptor.forClass(ContentItemEntity.class);
        verify(itemRepo, org.mockito.Mockito.atLeastOnce()).save(itemCaptor.capture());
        ContentItemEntity savedItem = itemCaptor.getAllValues().get(itemCaptor.getAllValues().size() - 1);
        assertEquals(4, savedItem.getCurrentVersion());
        assertEquals("第 2 版标题", savedItem.getTitle());
        assertEquals("/old", savedItem.getRoutePath());

        verify(audit).write(eq("admin"), eq("CONTENT_VERSION_REVERT"), eq("ContentItem"),
            eq(itemId.toString()), eq(site), eq("SUCCESS"), eq("v2 → v4"));
    }

    @Test
    void revertHonoursExplicitChangeNote() {
        stubPageItem(3);
        stubModelFields("body_t");
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1)).thenReturn(Optional.of(
            snapshotVersion(1, "{\"body_t\":\"初版\"}", "标题", "/p", "/t.ftl")));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentService.VersionInfo result = service.revertToVersion(itemId, 1, "误删内容回滚", "editor");

        assertEquals("误删内容回滚", result.note());
    }

    @Test
    void revertKeepsCurrentRouteAndTemplateWhenSnapshotHasNone() {
        // V100 迁移前的历史行 route_path/template_path 为空：回退时沿用当前值，避免 PAGE 因缺路径而 400
        stubPageItem(3);
        stubModelFields("body_t");
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1)).thenReturn(Optional.of(
            snapshotVersion(1, "{\"body_t\":\"初版\"}", null, null, null)));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentService.VersionInfo result = service.revertToVersion(itemId, 1, "admin");

        assertEquals("当前标题", result.title());
        assertEquals("/p", result.routePath());
        assertEquals("/t.ftl", result.templatePath());
    }

    @Test
    void revertRejectsSnapshotWithFieldsMissingFromCurrentModel() {
        stubPageItem(3);
        stubModelFields("body_t");
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1)).thenReturn(Optional.of(
            snapshotVersion(1, "{\"body\":\"旧字段名\",\"summary\":\"旧摘要\"}", "标题", "/p", "/t.ftl")));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.revertToVersion(itemId, 1, "admin"));

        assertEquals(ErrorCodes.VALIDATION_FAILED, ex.getCode());
        assertTrue(ex.getMessage().contains("body"), "错误信息应列出不兼容字段: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("summary"), "错误信息应列出不兼容字段: " + ex.getMessage());
    }

    @Test
    void revertUnknownVersionReturnsNotFound() {
        stubItemOnly(3);
        when(versionRepo.findByContentItemIdAndVersion(itemId, 99)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.revertToVersion(itemId, 99, "admin"));

        assertEquals(ErrorCodes.NOT_FOUND, ex.getCode());
    }

    @Test
    void saveVersionWritesItemFieldSnapshot() {
        stubPageItem(3);
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentService.VersionInfo result = service.saveVersion(itemId, "{\"body_t\":\"新\"}",
            "人工修改", "改写后的标题", "/p", "/t.ftl", "editor");

        assertEquals("改写后的标题", result.title());
        assertEquals("/p", result.routePath());
        assertEquals("/t.ftl", result.templatePath());
    }

    /** OBS-11-10：恢复产生的版本必须落来源版本号，界面据此标注「恢复自 v{n}」并默认按来源对比。 */
    @Test
    void revertRecordsSourceVersionOnTheNewVersionRow() {
        stubPageItem(3);
        stubModelFields("body_t");
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2)).thenReturn(Optional.of(
            snapshotVersion(2, "{\"body_t\":\"第 2 版正文\"}", "第 2 版标题", "/old", "/old.ftl")));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentService.VersionInfo result = service.revertToVersion(itemId, 2, "admin");

        assertEquals(4, result.version());
        assertEquals(2, result.revertedFrom().intValue());
        ArgumentCaptor<ContentVersionEntity> captor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(captor.capture());
        assertEquals(2, captor.getValue().getRevertedFrom().intValue());
    }

    /** OBS-11-10：非恢复路径（人工保存 / AI 采纳）不写来源版本，默认对比区间仍为「上一版本 → 新版本」。 */
    @Test
    void ordinarySaveAndAiVersionLeaveSourceVersionEmpty() {
        stubPageItem(3);
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentService.VersionInfo manual = service.saveVersion(itemId, "{\"body_t\":\"人工\"}",
            "人工修改", "标题", "/p", "/p.ftl", "editor");
        ContentService.VersionInfo ai = service.saveAiVersion(itemId, "{\"body_t\":\"AI\"}",
            "AI 建议采纳", "标题", "/p", "/p.ftl", "editor", "deepseek-v4-flash", 3);

        assertNull(manual.revertedFrom());
        assertNull(ai.revertedFrom());
    }

    /** OBS-11-10：版本历史返回体带上来源版本号（界面「恢复自 v{n}」标签与行内对比入口依赖它）。 */
    @Test
    void versionHistoryExposesSourceVersion() {
        ContentVersionEntity restored = snapshotVersion(4, "{\"body_t\":\"正文\"}", "标题", "/p", "/p.ftl")
            .withRevertedFrom(1);
        when(versionRepo.findByContentItemIdOrderByVersionDesc(itemId)).thenReturn(List.of(restored));

        ContentService.VersionInfo info = service.versions(itemId).get(0);

        assertEquals(4, info.version());
        assertEquals(1, info.revertedFrom().intValue());
    }

    @Test
    void versionHistoryExposesItemFieldSnapshot() {
        ContentVersionEntity v = snapshotVersion(2, "{\"body_t\":\"正文\"}", "历史标题", "/old", "/old.ftl");
        when(versionRepo.findByContentItemIdOrderByVersionDesc(itemId)).thenReturn(List.of(v));

        ContentService.VersionInfo info = service.versions(itemId).get(0);

        assertEquals("历史标题", info.title());
        assertEquals("/old", info.routePath());
        assertEquals("/old.ftl", info.templatePath());
    }

    /** OBS-11-04：AI 版本落库的 actor 是用户 ID，版本历史需按用户名展示（列表一次批量解析）。 */
    @Test
    void versionHistoryResolvesActorUserIdToUsername() {
        UUID actorId = UUID.randomUUID();
        ContentVersionEntity aiVersion = ContentVersionEntity.create(itemId, 3,
            "{\"body_t\":\"AI 正文\"}", 1, actorId.toString(), "AI 建议采纳")
            .withItemSnapshot("AI 标题", "/p", "/p.ftl");
        when(versionRepo.findByContentItemIdOrderByVersionDesc(itemId)).thenReturn(List.of(aiVersion));
        com.powercms.cms.identity.entity.UserEntity user =
            new com.powercms.cms.identity.entity.UserEntity();
        user.setId(actorId);
        user.setUsername("editor.s1");
        when(userRepo.findAllById(any())).thenReturn(List.of(user));

        ContentService.VersionInfo info = service.versions(itemId).get(0);

        assertEquals(actorId.toString(), info.createdBy());
        assertEquals("editor.s1", info.createdByName());
        verify(userRepo).findAllById(any());
    }

    /** OBS-11-04：actor 本身就是用户名时原样返回（人工保存版本的既有口径）。 */
    @Test
    void versionHistoryKeepsUsernameActorAsIs() {
        when(versionRepo.findByContentItemIdOrderByVersionDesc(itemId))
            .thenReturn(List.of(snapshotVersion(2, "{\"body_t\":\"正文\"}", "标题", "/p", "/p.ftl")));

        ContentService.VersionInfo info = service.versions(itemId).get(0);

        assertEquals("editor", info.createdBy());
        assertEquals("editor", info.createdByName());
    }
}
