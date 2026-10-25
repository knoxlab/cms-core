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
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.outbox.OutboxService;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-AI-GOV-001：AI 参与标识统一写入 + 审计 + 详情展示。 */
class ContentServiceAiIdentityTest {

    private ContentItemRepository itemRepo;
    private ContentTypeRepository ctRepo;
    private AuditService audit;
    private ContentService service;

    private final UUID site = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        itemRepo = mock(ContentItemRepository.class);
        ctRepo = mock(ContentTypeRepository.class);
        audit = mock(AuditService.class);
        service = new ContentService(itemRepo, mock(CategoryRepository.class),
            mock(ContentVersionRepository.class), ctRepo,
            mock(FieldDefinitionRepository.class), mock(SiteFolderRepository.class),
            mock(ClassComposeService.class), null, new ObjectMapper(),
            mock(OutboxService.class), mock(ApplicationEventPublisher.class),
            mock(ObjectPermissionService.class), audit,
            mock(AiDirectPublishGate.class), ActorNameResolver.passThrough(), null);

        ContentItemEntity item = new ContentItemEntity();
        item.setId(itemId);
        item.setSiteId(site);
        item.setTitle("标题");
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(UUID.randomUUID());
        type.setObjectType("PAGE");
        when(ctRepo.findById(any())).thenReturn(Optional.of(type));
    }

    @Test
    void markAiGeneratedWritesIdentityAndAudit() {
        ContentService.ContentInfo info = service.markAiGenerated(itemId, "deepseek-chat", 3, "agent-1");

        assertThat(info.aiGenerated()).isTrue();
        assertThat(info.aiModel()).isEqualTo("deepseek-chat");
        assertThat(info.aiPromptVersion()).isEqualTo(3);
        assertThat(info.aiGeneratedAt()).isNotNull();

        verify(audit).write(eq("agent-1"), eq("ai.content.generated"), eq("ContentItem"),
            eq(itemId.toString()), eq(site), eq("success"), anyString());
    }
}
