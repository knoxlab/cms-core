package com.powercms.cms.workflow;

import com.powercms.cms.workflow.entity.NotificationDeliveryEntity;
import com.powercms.cms.workflow.repo.NotificationDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailNotificationChannelTest {

    @Test
    void disabledDoesNotSend() {
        NotificationDeliveryRepository repo = mock(NotificationDeliveryRepository.class);
        EmailNotificationChannel.MailSenderFactory factory = mock(EmailNotificationChannel.MailSenderFactory.class);
        var channel = new EmailNotificationChannel(repo, username -> Optional.of("a@b.c"), factory, false, "from@x");
        channel.send(event(), "reviewer", "t", "b", "k1");
        verify(repo, never()).save(any());
        verify(factory, never()).create();
    }

    @Test
    void missingEmailRecordsSkipped() {
        NotificationDeliveryRepository repo = mock(NotificationDeliveryRepository.class);
        when(repo.existsByIdempotencyKeyAndChannelAndStatus(any(), any(), any())).thenReturn(false);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        EmailNotificationChannel.MailSenderFactory factory = mock(EmailNotificationChannel.MailSenderFactory.class);
        var channel = new EmailNotificationChannel(repo, username -> Optional.empty(), factory, true, "from@x");
        channel.send(event(), "reviewer", "t", "b", "k2");
        verify(factory, never()).create();
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationDeliveryEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationDeliveryEntity.SKIPPED);
    }

    @Test
    void sentOnceThenDuplicateSkipped() {
        NotificationDeliveryRepository repo = mock(NotificationDeliveryRepository.class);
        when(repo.existsByIdempotencyKeyAndChannelAndStatus(eq("email:k3"),
            eq(NotificationDeliveryEntity.CHANNEL_EMAIL), eq(NotificationDeliveryEntity.SENT)))
            .thenReturn(false, true);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailNotificationChannel.MailSenderFactory factory = mock(EmailNotificationChannel.MailSenderFactory.class);
        when(factory.create()).thenReturn(sender);
        var channel = new EmailNotificationChannel(repo, username -> Optional.of("r@x.com"), factory, true, "from@x");
        WorkflowTaskEvent ev = event();
        channel.send(ev, "reviewer", "审核任务", "body", "k3");
        channel.send(ev, "reviewer", "审核任务", "body", "k3");
        verify(sender).send(any(SimpleMailMessage.class));
    }

    private static WorkflowTaskEvent event() {
        return new WorkflowTaskEvent(UUID.randomUUID(), WorkflowTaskEvent.SUBMITTED, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "首页", "editor", "editor", "reviewer", null);
    }

    // ---- DEF-CFG-03：组织级发件身份与系统自引用 URL 接入真实发信链路 ----

    @Test
    void organizationFromAddressOverridesStaticFallback() {
        var sent = sendAndCapture(SystemMailContext.EMPTY, "noreply@static.example.com");

        assertThat(sent.getFrom()).isEqualTo("noreply@static.example.com");
    }

    @Test
    void organizationFromAddressWinsWhenConfigured() {
        var sent = sendAndCapture(mailContext(Optional.of("no-reply@acme.example.com"), Optional.empty()),
            "noreply@static.example.com");

        assertThat(sent.getFrom()).isEqualTo("no-reply@acme.example.com");
    }

    @Test
    void portalUrlProducesAbsoluteTaskLink() {
        UUID taskId = UUID.randomUUID();
        var sent = sendAndCapture(mailContext(Optional.empty(), Optional.of("https://portal.example.com")),
            "noreply@static.example.com", taskId);

        assertThat(sent.getText()).contains("查看任务：https://portal.example.com/tasks/" + taskId);
    }

    @Test
    void trailingSlashInPortalUrlDoesNotDoubleUp() {
        UUID taskId = UUID.randomUUID();
        var sent = sendAndCapture(mailContext(Optional.empty(), Optional.of("https://portal.example.com/")),
            "noreply@static.example.com", taskId);

        assertThat(sent.getText()).contains("查看任务：https://portal.example.com/tasks/" + taskId);
        assertThat(sent.getText()).doesNotContain("//tasks/");
    }

    @Test
    void missingPortalUrlOmitsLinkLineEntirely() {
        var sent = sendAndCapture(SystemMailContext.EMPTY, "noreply@static.example.com");

        // Freemarker 对缺键做 ?has_content 判定会抛异常，故模板必须整行省略而不是渲染出半截。
        assertThat(sent.getText()).doesNotContain("查看任务");
    }

    private SimpleMailMessage sendAndCapture(SystemMailContext mailContext, String staticFrom) {
        return sendAndCapture(mailContext, staticFrom, UUID.randomUUID());
    }

    private SimpleMailMessage sendAndCapture(SystemMailContext mailContext, String staticFrom, UUID taskId) {
        NotificationDeliveryRepository repo = mock(NotificationDeliveryRepository.class);
        when(repo.existsByIdempotencyKeyAndChannelAndStatus(any(), any(), any())).thenReturn(false);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailNotificationChannel.MailSenderFactory factory = mock(EmailNotificationChannel.MailSenderFactory.class);
        when(factory.create()).thenReturn(sender);

        var channel = new EmailNotificationChannel(repo, username -> Optional.of("r@x.com"), factory,
            null, mailContext, true, staticFrom);
        channel.send(event(taskId), "reviewer", "审核任务", "body", "k-" + taskId);

        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    private static SystemMailContext mailContext(Optional<String> from, Optional<String> portalUrl) {
        return new SystemMailContext() {
            @Override
            public Optional<String> fromAddress() {
                return from;
            }

            @Override
            public Optional<String> portalUrl() {
                return portalUrl;
            }
        };
    }

    private static WorkflowTaskEvent event(UUID taskId) {
        return new WorkflowTaskEvent(UUID.randomUUID(), WorkflowTaskEvent.SUBMITTED, UUID.randomUUID(),
            taskId, UUID.randomUUID(), "首页", "editor", "editor", "reviewer", null);
    }
}
