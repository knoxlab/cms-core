package com.powercms.cms.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.compose.ClassComposeService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.ActorNameResolver;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.outbox.OutboxService;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批量状态流转（内容搜索页批量条：提交审核 / 发布 / 设为草稿 / 归档）。
 *
 * <p>核心契约：<b>逐条独立成败</b>——合法目标状态白名单、空列表拒绝、
 * 单条失败（如内容不存在）只进 failed、不影响其余条目。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentServiceBatchStatusTest {

    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository ctRepo;
    @Mock FieldDefinitionRepository fieldRepo;
    @Mock ClassComposeService classCompose;
    @Mock AuditService audit;
    @Mock AiDirectPublishGate gate;

    private ContentService service;
    private final UUID site = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentService(itemRepo, mock(CategoryRepository.class), versionRepo, ctRepo,
            fieldRepo, mock(SiteFolderRepository.class), classCompose, null,
            new ObjectMapper(), mock(OutboxService.class), mock(ApplicationEventPublisher.class),
            mock(ObjectPermissionService.class), audit, gate,
            ActorNameResolver.passThrough(), null);
        lenient().when(classCompose.applyOnSave(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
    }

    private ContentItemEntity item(String status) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(UUID.randomUUID());
        item.setSiteId(site);
        item.setContentTypeId(UUID.randomUUID());
        item.setTitle("批量目标");
        item.setStatus(status);
        return item;
    }

    @Test
    void 空列表与非法目标状态直接拒绝() {
        BusinessException empty = assertThrows(BusinessException.class,
            () -> service.batchStatus(List.of(), "ARCHIVED"));
        assertEquals(ErrorCodes.VALIDATION_FAILED, empty.getCode());

        BusinessException bad = assertThrows(BusinessException.class,
            () -> service.batchStatus(List.of(UUID.randomUUID()), "DELETED"));
        assertEquals(ErrorCodes.VALIDATION_FAILED, bad.getCode());
    }

    @Test
    void 逐条独立成败_失败条目不影响其余() {
        ContentItemEntity ok = item(ContentItemEntity.STATUS_PUBLISHED);
        UUID missing = UUID.randomUUID();
        when(itemRepo.findById(ok.getId())).thenReturn(Optional.of(ok));
        when(itemRepo.findById(missing)).thenReturn(Optional.empty());

        ContentService.BatchResult result =
            service.batchStatus(List.of(ok.getId(), missing), "ARCHIVED");

        assertEquals(List.of(ok.getId().toString()), result.succeeded());
        assertEquals(1, result.failed().size());
        assertEquals(missing.toString(), result.failed().get(0).id());
        // 成功条目真实落到归档状态（复用 updateStatus 的审计/事件口径）
        assertEquals(ContentItemEntity.STATUS_ARCHIVED, ok.getStatus());
    }
}
