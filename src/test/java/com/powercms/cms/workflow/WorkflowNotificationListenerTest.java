package com.powercms.cms.workflow;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowNotificationListenerTest {

    private static NotificationService prefsOn() {
        NotificationService notifications = mock(NotificationService.class);
        when(notifications.channelEnabled(any(), any(), any())).thenReturn(true);
        return notifications;
    }

    @Test
    void submittedNotifiesAssignee() {
        NotificationService notifications = prefsOn();
        EmailNotificationChannel email = mock(EmailNotificationChannel.class);
        var listener = new WorkflowNotificationListener(notifications, email, true);
        UUID taskId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        listener.onWorkflowTask(new WorkflowTaskEvent(
            UUID.randomUUID(), WorkflowTaskEvent.SUBMITTED, UUID.randomUUID(), taskId, contentId,
            "首页", "editor", "editor", "reviewer", null));
        verify(notifications).notifyOnce(eq("reviewer"), eq("WORKFLOW_APPROVE"), eq("审核任务"),
            any(), eq("CONTENT"), eq(contentId),
            eq(WorkflowTaskEvent.SUBMITTED + ":review-task:" + taskId + ":reviewer"),
            eq("/tasks/" + taskId), eq(NotificationLevel.INFO));
        verify(email).send(any(), eq("reviewer"), eq("审核任务"), any(),
            eq(WorkflowTaskEvent.SUBMITTED + ":review-task:" + taskId + ":reviewer"));
    }

    @Test
    void disabledSkipsDelivery() {
        NotificationService notifications = mock(NotificationService.class);
        EmailNotificationChannel email = mock(EmailNotificationChannel.class);
        var listener = new WorkflowNotificationListener(notifications, email, false);
        listener.onWorkflowTask(new WorkflowTaskEvent(
            UUID.randomUUID(), WorkflowTaskEvent.REJECTED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "x", "r", "editor", "reviewer", "no"));
        verify(notifications, never()).notifyOnce(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(email, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void rejectedNotifiesSubmitter() {
        NotificationService notifications = prefsOn();
        EmailNotificationChannel email = mock(EmailNotificationChannel.class);
        var listener = new WorkflowNotificationListener(notifications, email, true);
        UUID taskId = UUID.randomUUID();
        listener.onWorkflowTask(new WorkflowTaskEvent(
            UUID.randomUUID(), WorkflowTaskEvent.REJECTED, UUID.randomUUID(), taskId, UUID.randomUUID(),
            "首页", "reviewer", "editor", "reviewer", "错别字"));
        verify(notifications).notifyOnce(eq("editor"), eq("WORKFLOW_REJECT"), eq("审核驳回"),
            any(), any(), any(),
            eq(WorkflowTaskEvent.REJECTED + ":review-task:" + taskId + ":editor"), any(),
            eq(NotificationLevel.WARNING));
    }

    @Test
    void inAppPreferenceOffSkipsStationNotification() {
        NotificationService notifications = mock(NotificationService.class);
        when(notifications.channelEnabled(eq("reviewer"), eq(WorkflowTaskEvent.SUBMITTED), eq("IN_APP")))
            .thenReturn(false);
        when(notifications.channelEnabled(eq("reviewer"), eq(WorkflowTaskEvent.SUBMITTED), eq("EMAIL")))
            .thenReturn(true);
        EmailNotificationChannel email = mock(EmailNotificationChannel.class);
        var listener = new WorkflowNotificationListener(notifications, email, true);
        listener.onWorkflowTask(new WorkflowTaskEvent(
            UUID.randomUUID(), WorkflowTaskEvent.SUBMITTED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "首页", "editor", "editor", "reviewer", null));
        verify(notifications, never()).notifyOnce(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(email).send(any(), eq("reviewer"), eq("审核任务"), any(), any());
    }

    /** DEF-11-22：级别按事件映射（驳回 → WARNING）。 */
    @Test
    void rejectedNotificationCarriesWarningLevel() {
        NotificationService notifications = prefsOn();
        EmailNotificationChannel email = mock(EmailNotificationChannel.class);
        var listener = new WorkflowNotificationListener(notifications, email, true);
        listener.onWorkflowTask(new WorkflowTaskEvent(
            UUID.randomUUID(), WorkflowTaskEvent.REJECTED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "首页", "reviewer", "editor", "reviewer", "错别字"));
        verify(notifications).notifyOnce(eq("editor"), eq("WORKFLOW_REJECT"), eq("审核驳回"),
            any(), any(), any(), any(), any(), eq(NotificationLevel.WARNING));
    }

    /** DEF-11-22：审核通过 → SUCCESS。 */
    @Test
    void approvedNotificationCarriesSuccessLevel() {
        NotificationService notifications = prefsOn();
        EmailNotificationChannel email = mock(EmailNotificationChannel.class);
        var listener = new WorkflowNotificationListener(notifications, email, true);
        listener.onWorkflowTask(new WorkflowTaskEvent(
            UUID.randomUUID(), WorkflowTaskEvent.APPROVED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "首页", "reviewer", "editor", "reviewer", null));
        verify(notifications).notifyOnce(eq("editor"), eq("WORKFLOW_APPROVE"), eq("审核通过"),
            any(), any(), any(), any(), any(), eq(NotificationLevel.SUCCESS));
    }

    /** DEF-11-22：超时提醒 → WARNING；超时后自动升级（动作键 ESCALATE）→ ERROR。 */
    @Test
    void timeoutNotificationLevelDependsOnEscalation() {
        NotificationService notifications = prefsOn();
        EmailNotificationChannel email = mock(EmailNotificationChannel.class);
        var listener = new WorkflowNotificationListener(notifications, email, true);
        listener.onWorkflowTask(new WorkflowTaskEvent(
            UUID.randomUUID(), WorkflowTaskEvent.TIMEOUT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "首页", "system", "editor", "reviewer", "REMINDER"));
        listener.onWorkflowTask(new WorkflowTaskEvent(
            UUID.randomUUID(), WorkflowTaskEvent.TIMEOUT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "首页", "system", "editor", "admin", "ESCALATE"));
        verify(notifications).notifyOnce(eq("reviewer"), eq("WORKFLOW_DELEGATE"), eq("审核超时"),
            any(), any(), any(), any(), any(), eq(NotificationLevel.WARNING));
        verify(notifications).notifyOnce(eq("admin"), eq("WORKFLOW_DELEGATE"), eq("审核超时"),
            any(), any(), any(), any(), any(), eq(NotificationLevel.ERROR));
    }
}
