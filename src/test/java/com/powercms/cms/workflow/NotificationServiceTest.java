package com.powercms.cms.workflow;

import com.powercms.cms.workflow.entity.NotificationEntity;
import com.powercms.cms.workflow.repo.NotificationPreferenceRepository;
import com.powercms.cms.workflow.repo.NotificationRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository repo;
    @Mock NotificationPreferenceRepository prefRepo;
    NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repo, prefRepo);
    }

    @Test
    void notifyOnceSkipsDuplicateKey() {
        when(repo.findByIdempotencyKey("k")).thenReturn(Optional.of(NotificationEntity.create("a", "T", "t", "b", "C", UUID.randomUUID())));
        assertThat(service.notifyOnce("a", "T", "t", "b", "C", UUID.randomUUID(), "k", "/x")).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void notifyOncePersistsFirstDelivery() {
        when(repo.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        UUID related = UUID.randomUUID();
        var saved = service.notifyOnce("reviewer", "WORKFLOW_APPROVE", "审核任务", "body", "CONTENT", related, "k", "/tasks/1");
        assertThat(saved.getIdempotencyKey()).isEqualTo("k");
        assertThat(saved.getLink()).isEqualTo("/tasks/1");
        assertThat(saved.getGroupId()).isEqualTo(related);
        // 旧签名未指定级别：保持 INFO，不改变既有行为
        assertThat(saved.getLevel()).isEqualTo(NotificationLevel.INFO);
    }

    /** DEF-11-22：显式级别需落库并做取值收敛。 */
    @Test
    void notifyOncePersistsExplicitLevel() {
        when(repo.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(repo.findByIdempotencyKey("k2")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        var success = service.notifyOnce("editor", "WORKFLOW_APPROVE", "审核通过", "body", "CONTENT",
            UUID.randomUUID(), "k1", "/tasks/1", NotificationLevel.SUCCESS);
        var fallback = service.notifyOnce("editor", "WORKFLOW_APPROVE", "审核通过", "body", "CONTENT",
            UUID.randomUUID(), "k2", "/tasks/1", "not-a-level");
        assertThat(success.getLevel()).isEqualTo(NotificationLevel.SUCCESS);
        assertThat(fallback.getLevel()).isEqualTo(NotificationLevel.INFO);
    }

    @Test
    void markAllReadUpdatesUnreadRows() {
        var n = NotificationEntity.create("u", "T", "t", "b", "C", UUID.randomUUID());
        when(repo.findByRecipientAndReadAtIsNull("u")).thenReturn(List.of(n));
        assertThat(service.markAllRead("u")).isEqualTo(1);
        assertThat(n.getReadAt()).isNotNull();
        verify(repo).saveAll(List.of(n));
    }

    @Test
    void missingPreferenceDefaultsEnabled() {
        when(prefRepo.findByUsernameAndEventTypeAndChannel("u", WorkflowTaskEvent.APPROVED, "EMAIL"))
            .thenReturn(Optional.empty());
        assertThat(service.channelEnabled("u", WorkflowTaskEvent.APPROVED, "EMAIL")).isTrue();
    }

    // ---- 渠道白名单（社区版仅站内 IN_APP + SMTP EMAIL） ------------------------------

    /** 扩展渠道（IM/短信/WEBHOOK）未注册：拒绝落库并返回 VALIDATION_FAILED。 */
    @Test
    void extendedChannelPreferencesAreRejected() {
        assertThatThrownBy(() -> service.savePreferences("u", List.of(
            new NotificationService.PrefInfo(WorkflowTaskEvent.APPROVED, "WEBHOOK", true))))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.VALIDATION_FAILED));
        verify(prefRepo, never()).save(any());
    }

    /** 站内 IN_APP 与 SMTP EMAIL 属基础渠道，必须可用。 */
    @Test
    void basicChannelPreferencesAreSaved() {
        when(prefRepo.findByUsernameAndEventTypeAndChannel(any(), any(), any()))
            .thenReturn(Optional.empty());

        service.savePreferences("u", List.of(
            new NotificationService.PrefInfo(WorkflowTaskEvent.APPROVED, "IN_APP", true),
            new NotificationService.PrefInfo(WorkflowTaskEvent.APPROVED, "EMAIL", true)));

        verify(prefRepo, org.mockito.Mockito.times(2)).save(any());
    }
}
