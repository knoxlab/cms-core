package com.powercms.cms.workflow;

import java.util.UUID;

/**
 * 审核任务领域事件。事务提交后由 {@link WorkflowNotificationListener} 消费并投递站内通知。
 */
public record WorkflowTaskEvent(
    UUID eventId,
    String eventType,
    UUID siteId,
    UUID taskId,
    UUID contentItemId,
    String contentTitle,
    String actor,
    String submitter,
    String assignee,
    String comment
) {
    public static final String SUBMITTED = "review-task.submitted";
    public static final String APPROVED = "review-task.approved";
    public static final String REJECTED = "review-task.rejected";
    public static final String CANCELLED = "review-task.cancelled";
    public static final String DELEGATED = "review-task.delegated";
    public static final String SUCCEEDED = "review-task.succeeded";
    public static final String TIMEOUT = "workflow.timeout.warning";
}
