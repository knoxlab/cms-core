package com.powercms.cms.workflow;

import com.powercms.cms.workflow.repo.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：DEF-11-01 —— AFTER_COMMIT 回调里的站内通知会静默丢失。
 *
 * <p>本测试类刻意不加 {@code @Transactional}：只有事件在真实事务中发布并提交后，
 * {@link WorkflowNotificationListener} 的 AFTER_COMMIT 回调才会执行，从而覆盖
 * 「通知写入加入已提交事务导致不落库」的场景。修复前该断言失败（notification 表 0 行）。</p>
 */
@SpringBootTest
class WorkflowInAppNotificationIntegrationTest {

    @Autowired private ApplicationEventPublisher events;
    @Autowired private NotificationRepository notificationRepo;
    @Autowired private NotificationService notificationService;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    void afterCommitListenerPersistsInAppNotification() {
        String recipient = "reviewer.it-" + UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        new TransactionTemplate(txManager).executeWithoutResult(status -> events.publishEvent(
            new WorkflowTaskEvent(UUID.randomUUID(), WorkflowTaskEvent.SUBMITTED,
                UUID.randomUUID(), taskId, UUID.randomUUID(), "回归测试内容",
                "admin", "admin", recipient, null)));

        assertThat(notificationRepo.findByRecipientOrderByCreatedAtDesc(recipient))
            .singleElement()
            .satisfies(n -> {
                assertThat(n.getType()).isEqualTo("WORKFLOW_APPROVE");
                assertThat(n.getTitle()).isEqualTo("审核任务");
                assertThat(n.getBody()).contains("回归测试内容");
                assertThat(n.getLink()).isEqualTo("/tasks/" + taskId);
                assertThat(n.getReadAt()).isNull();
                assertThat(n.getLevel()).isEqualTo(NotificationLevel.INFO);
            });
    }

    /**
     * DEF-11-22：事件 → 级别映射必须端到端落到 {@code notification.level}，并经通知列表接口
     * 回带给前端（否则前端四色标签中的 SUCCESS/WARNING/ERROR 仍是「仅渲染可用」）。
     */
    @Test
    void eventDrivenLevelsReachNotificationRowsAndListApi() {
        String recipient = "reviewer.it-level-" + UUID.randomUUID();
        publish(WorkflowTaskEvent.APPROVED, recipient, UUID.randomUUID(), null);
        publish(WorkflowTaskEvent.REJECTED, recipient, UUID.randomUUID(), null);
        publish(WorkflowTaskEvent.TIMEOUT, recipient, UUID.randomUUID(), "REMINDER");
        publish(WorkflowTaskEvent.TIMEOUT, recipient, UUID.randomUUID(), "ESCALATE");

        var rows = notificationRepo.findByRecipientOrderByCreatedAtDesc(recipient);
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(n -> n.getLevel())
            .containsExactlyInAnyOrder(NotificationLevel.SUCCESS, NotificationLevel.WARNING,
                NotificationLevel.WARNING, NotificationLevel.ERROR);

        // 列表接口（/api/workflow/notifications 的数据来源）必须回带 level
        assertThat(notificationService.list(recipient, false)).hasSize(4)
            .allSatisfy(info -> assertThat(NotificationLevel.ALL).contains(info.level()));
    }

    /** 提交人与受理人都设为同一收件人，覆盖「通知提交人」与「通知受理人」两条投递路径。 */
    private void publish(String eventType, String recipient, UUID taskId, String comment) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> events.publishEvent(
            new WorkflowTaskEvent(UUID.randomUUID(), eventType, UUID.randomUUID(), taskId, UUID.randomUUID(),
                "级别映射回归内容", "admin", recipient, recipient, comment)));
    }

    @Test
    void sameEventDeliveredTwiceIsIdempotent() {
        String recipient = "reviewer.it-dup-" + UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        for (int i = 0; i < 2; i++) {
            new TransactionTemplate(txManager).executeWithoutResult(status -> events.publishEvent(
                new WorkflowTaskEvent(eventId, WorkflowTaskEvent.SUBMITTED,
                    UUID.randomUUID(), taskId, UUID.randomUUID(), "重复投递内容",
                    "admin", "admin", recipient, null)));
        }

        assertThat(notificationRepo.findByRecipientOrderByCreatedAtDesc(recipient)).hasSize(1);
    }
}
