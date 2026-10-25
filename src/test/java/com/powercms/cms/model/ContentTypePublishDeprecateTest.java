package com.powercms.cms.model;

import com.powercms.cms.model.dto.ContentTypeResponse;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.*;
import com.powercms.cms.content.relation.ContentRelationRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.model.sandbox.ScriptSandboxService;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布即废弃（FR-CM-CT-014 扩展）：同 code 只保留一个 PUBLISHED 生效版本。
 */
@ExtendWith(MockitoExtension.class)
class ContentTypePublishDeprecateTest {

    @Mock private ContentTypeRepository ctRepo;
    @Mock private FieldDefinitionRepository fieldRepo;
    @Mock private FormLayoutRepository layoutRepo;
    @Mock private ContentTypeChangeLogRepository logRepo;
    @Mock private ContentTypeLifecycleRepository lifecycleRepo;
    @Mock private ContentItemRepository itemRepo;
    @Mock private ContentRelationRepository relationRepo;
    @Mock private SiteRepository siteRepo;
    @Mock private ContentModelValidator validator;
    @Mock private ScriptSandboxService sandbox;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper om;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ContentTypeMigrationService migrationService;

    private ContentTypeService service;
    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentTypeService(ctRepo, fieldRepo, layoutRepo, logRepo, lifecycleRepo,
            itemRepo, relationRepo, siteRepo, validator, sandbox, om, eventPublisher, migrationService);
    }

    @Test
    void publishDeprecatesOlderPublishedVersionWithSameCode() {
        ContentTypeEntity old = entity(siteId, "news", 1, ContentTypeEntity.STATUS_PUBLISHED);
        ContentTypeEntity draft = entity(siteId, "news", 2, ContentTypeEntity.STATUS_DRAFT);
        when(ctRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        lenient().when(ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, "news"))
            .thenReturn(List.of(old, draft));

        ContentTypeResponse response = service.publish(siteId, draft.getId(), "tester", null);

        assertThat(response.status()).isEqualTo(ContentTypeEntity.STATUS_PUBLISHED);
        assertThat(old.getStatus()).isEqualTo(ContentTypeEntity.STATUS_DEPRECATED);
    }

    @Test
    void publishDeprecatesMultipleStalePublishedRows() {
        ContentTypeEntity v1 = entity(siteId, "news", 1, ContentTypeEntity.STATUS_PUBLISHED);
        ContentTypeEntity v2 = entity(siteId, "news", 2, ContentTypeEntity.STATUS_PUBLISHED);
        ContentTypeEntity v3 = entity(siteId, "news", 3, ContentTypeEntity.STATUS_DRAFT);
        when(ctRepo.findById(v3.getId())).thenReturn(Optional.of(v3));
        lenient().when(ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, "news"))
            .thenReturn(List.of(v1, v2, v3));

        service.publish(siteId, v3.getId(), "tester", null);

        assertThat(v1.getStatus()).isEqualTo(ContentTypeEntity.STATUS_DEPRECATED);
        assertThat(v2.getStatus()).isEqualTo(ContentTypeEntity.STATUS_DEPRECATED);
        assertThat(v3.getStatus()).isEqualTo(ContentTypeEntity.STATUS_PUBLISHED);
    }

    @Test
    void publishKeepsOtherCodesUntouched() {
        ContentTypeEntity other = entity(siteId, "banner", 1, ContentTypeEntity.STATUS_PUBLISHED);
        ContentTypeEntity draft = entity(siteId, "news", 2, ContentTypeEntity.STATUS_DRAFT);
        when(ctRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        lenient().when(ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, "news"))
            .thenReturn(List.of(draft));

        service.publish(siteId, draft.getId(), "tester", null);

        assertThat(other.getStatus()).isEqualTo(ContentTypeEntity.STATUS_PUBLISHED);
    }

    @Test
    void publishTriggersMigrationFromDeprecatedRows() {
        ContentTypeEntity old = entity(siteId, "news", 1, ContentTypeEntity.STATUS_PUBLISHED);
        ContentTypeEntity draft = entity(siteId, "news", 2, ContentTypeEntity.STATUS_DRAFT);
        when(ctRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, "news"))
            .thenReturn(List.of(old, draft));

        service.publish(siteId, draft.getId(), "tester", "{\"mode\":\"SKIP\"}");

        verify(migrationService).migrate(org.mockito.ArgumentMatchers.eq(siteId),
            org.mockito.ArgumentMatchers.eq(old.getId()),
            org.mockito.ArgumentMatchers.eq(draft),
            org.mockito.ArgumentMatchers.any());
    }

    private ContentTypeEntity entity(UUID siteId, String code, int version, String status) {
        ContentTypeEntity ct = new ContentTypeEntity();
        ct.setId(UUID.randomUUID());
        ct.setSiteId(siteId);
        ct.setCode(code);
        ct.setName(code);
        ct.setVersion(version);
        ct.setStatus(status);
        ct.setObjectType("PAGE");
        return ct;
    }
}
