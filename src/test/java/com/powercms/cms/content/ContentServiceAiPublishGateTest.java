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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-AI-GOV-004：AI 参与内容发布拦截。
 * 默认 agent_direct_publish=false ⟺ 必须审批：DRAFT/IN_REVIEW 直接发布被拒并审计；
 * WORKFLOW 末步、已审批态（APPROVED/PUBLISHED/WITHDRAWN）或站点放行直发时允许。
 */
@ExtendWith(MockitoExtension.class)
class ContentServiceAiPublishGateTest {

    @Mock ContentItemRepository itemRepo;
    @Mock AiDirectPublishGate gate;
    @Mock AuditService audit;

    private ContentService service;
    private final UUID site = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentService(itemRepo, mock(CategoryRepository.class),
            mock(ContentVersionRepository.class), mock(ContentTypeRepository.class),
            mock(FieldDefinitionRepository.class), mock(SiteFolderRepository.class),
            mock(ClassComposeService.class), null, new ObjectMapper(),
            mock(OutboxService.class), mock(ApplicationEventPublisher.class),
            mock(ObjectPermissionService.class), audit, gate,
            ActorNameResolver.passThrough(), null);
    }

    private ContentItemEntity aiItem(String status) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(itemId);
        item.setSiteId(site);
        item.setStatus(status);
        item.setAiGenerated(true);
        return item;
    }

    @Test
    void aiDraftDirectPublishRejectedAndAudited() {
        ContentItemEntity item = aiItem(ContentItemEntity.STATUS_DRAFT);
        when(gate.allowsAgentDirectPublish(site)).thenReturn(false);
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED));
        assertEquals(ErrorCodes.AI_APPROVAL_REQUIRED, ex.getCode());
        verify(audit).write(eq("system"), eq("AI_PUBLISH_REJECTED"), eq("ContentItem"),
            eq(itemId.toString()), eq(site), eq("failed"), any());
    }

    @Test
    void aiInReviewDirectPublishRejected() {
        ContentItemEntity item = aiItem(ContentItemEntity.STATUS_IN_REVIEW);
        when(gate.allowsAgentDirectPublish(site)).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.enforceAiApprovalPolicy(item, ContentService.PublishSource.USER));
        assertEquals(ErrorCodes.AI_APPROVAL_REQUIRED, ex.getCode());
        verify(audit).write(eq("system"), eq("AI_PUBLISH_REJECTED"), eq("ContentItem"),
            eq(itemId.toString()), eq(site), eq("failed"), any());
    }

    @Test
    void workflowPublishAllowedForAiContent() {
        ContentItemEntity item = aiItem(ContentItemEntity.STATUS_IN_REVIEW);
        when(gate.allowsAgentDirectPublish(site)).thenReturn(false);
        // 审批链末步（WORKFLOW）发布放行
        assertDoesNotThrow(
            () -> service.enforceAiApprovalPolicy(item, ContentService.PublishSource.WORKFLOW));
        verify(audit, never()).write(any(), eq("AI_PUBLISH_REJECTED"), any(), any(), any(), any(), any());
    }

    @Test
    void approvedAiContentCanBePublishedManually() {
        ContentItemEntity item = aiItem(ContentItemEntity.STATUS_APPROVED);
        when(gate.allowsAgentDirectPublish(site)).thenReturn(false);
        assertDoesNotThrow(
            () -> service.enforceAiApprovalPolicy(item, ContentService.PublishSource.USER));
    }

    @Test
    void previouslyPublishedAiContentCanBeRepublished() {
        ContentItemEntity item = aiItem(ContentItemEntity.STATUS_PUBLISHED);
        when(gate.allowsAgentDirectPublish(site)).thenReturn(false);
        assertDoesNotThrow(
            () -> service.enforceAiApprovalPolicy(item, ContentService.PublishSource.USER));
    }

    @Test
    void agentDirectPublishEnabledAllowsAiDraftDirectPublish() {
        ContentItemEntity item = aiItem(ContentItemEntity.STATUS_DRAFT);
        when(gate.allowsAgentDirectPublish(site)).thenReturn(true);
        assertDoesNotThrow(
            () -> service.enforceAiApprovalPolicy(item, ContentService.PublishSource.USER));
        verify(audit, never()).write(any(), eq("AI_PUBLISH_REJECTED"), any(), any(), any(), any(), any());
    }

    @Test
    void nonAiContentIgnoresApprovalPolicy() {
        ContentItemEntity item = aiItem(ContentItemEntity.STATUS_DRAFT);
        item.setAiGenerated(false);
        assertDoesNotThrow(
            () -> service.enforceAiApprovalPolicy(item, ContentService.PublishSource.USER));
        verify(audit, never()).write(any(), eq("AI_PUBLISH_REJECTED"), any(), any(), any(), any(), any());
    }

    @Test
    void aiWithdrawnRepublishAllowed() {
        ContentItemEntity item = aiItem(ContentItemEntity.STATUS_WITHDRAWN);
        when(gate.allowsAgentDirectPublish(site)).thenReturn(false);
        assertDoesNotThrow(
            () -> service.enforceAiApprovalPolicy(item, ContentService.PublishSource.USER));
    }
}
