package com.powercms.cms.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务提交后投递工作流通知（FR-WF-006）。发送失败不影响已提交的审核事务。
 */
@Component
public class WorkflowNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNotificationListener.class);

    private final NotificationService notifications;
    private final EmailNotificationChannel email;
    private final boolean enabled;

    WorkflowNotificationListener(NotificationService notifications,
                                 EmailNotificationChannel email,
                                 @Value("${powercms.workflow.notification-enabled:true}") boolean enabled) {
        this.notifications = notifications;
        this.email = email;
        this.enabled = enabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkflowTask(WorkflowTaskEvent event) {
        if (!enabled || event == null) return;
        try {
            switch (event.eventType()) {
                case WorkflowTaskEvent.SUBMITTED -> notifyAssignee(event, "WORKFLOW_APPROVE",
                    "审核任务", "内容《" + event.contentTitle() + "》等待您审核");
                case WorkflowTaskEvent.APPROVED -> notifySubmitter(event, "WORKFLOW_APPROVE",
                    "审核通过", "内容《" + event.contentTitle() + "》已批准");
                case WorkflowTaskEvent.REJECTED -> notifySubmitter(event, "WORKFLOW_REJECT",
                    "审核驳回", "内容《" + event.contentTitle() + "》被驳回：" + nullToEmpty(event.comment()));
                case WorkflowTaskEvent.CANCELLED -> notifyAssignee(event, "WORKFLOW_DELEGATE",
                    "审核取消", "内容《" + event.contentTitle() + "》的审核任务已取消");
                case WorkflowTaskEvent.DELEGATED -> notifyAssignee(event, "WORKFLOW_DELEGATE",
                    "审核委派", "内容《" + event.contentTitle() + "》已委派给您审核");
                case WorkflowTaskEvent.SUCCEEDED -> notifySubmitter(event, "WORKFLOW_APPROVE",
                    "发布完成", "内容《" + event.contentTitle() + "》已按计划发布");
                case WorkflowTaskEvent.TIMEOUT -> notifyAssignee(event, "WORKFLOW_DELEGATE",
                    "审核超时", "内容《" + event.contentTitle() + "》的审核任务已超时");
                default -> log.debug("unhandled workflow event {}", event.eventType());
            }
        } catch (Exception ex) {
            log.error("workflow notification failed type={} task={}", event.eventType(), event.taskId(), ex);
        }
    }

    private void notifyAssignee(WorkflowTaskEvent event, String type, String title, String body) {
        deliver(event, event.assignee(), type, title, body);
    }

    private void notifySubmitter(WorkflowTaskEvent event, String type, String title, String body) {
        deliver(event, event.submitter(), type, title, body);
    }

    private void deliver(WorkflowTaskEvent event, String recipient, String type, String title, String body) {
        if (recipient == null || recipient.isBlank()) return;
        String idem = key(event, recipient);
        // DEF-11-22：级别按事件映射（超时事件携带动作键，ESCALATE 视为已自动升级）
        String level = NotificationLevel.forEvent(event.eventType(), event.comment());
        if (notifications.channelEnabled(recipient, event.eventType(), "IN_APP")) {
            notifications.notifyOnce(recipient, type, title, body, "CONTENT", event.contentItemId(),
                idem, "/tasks/" + event.taskId(), level);
        }
        if (notifications.channelEnabled(recipient, event.eventType(), "EMAIL")) {
            try {
                email.send(event, recipient, title, body, idem);
            } catch (Exception ex) {
                log.error("email notification failed type={} task={}", event.eventType(), event.taskId(), ex);
            }
        }
    }

    private static String key(WorkflowTaskEvent event, String recipient) {
        return event.eventType() + ":review-task:" + event.taskId() + ":" + recipient;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
