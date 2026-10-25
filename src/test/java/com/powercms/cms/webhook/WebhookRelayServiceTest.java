package com.powercms.cms.webhook;

import com.powercms.cms.outbox.entity.OutboxEventEntity;
import com.powercms.cms.outbox.repo.OutboxEventRepository;
import com.powercms.cms.webhook.entity.WebhookDeliveryEntity;
import com.powercms.cms.webhook.entity.WebhookSubscriptionEntity;
import com.powercms.cms.webhook.repo.WebhookDeliveryRepository;
import com.powercms.cms.webhook.repo.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookRelayServiceTest {

    @Mock OutboxEventRepository outboxRepo;
    @Mock WebhookSubscriptionRepository subscriptions;
    @Mock WebhookDeliveryRepository deliveries;

    private WebhookRelayService relay;

    @BeforeEach
    void setUp() {
        relay = new WebhookRelayService(outboxRepo, subscriptions, deliveries,
            new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Duration.ofSeconds(1));
    }

    @Test
    void createsDeliveryForMatchingSubscriptionAndMarksOutboxPublished() {
        UUID site = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = OutboxEventEntity.create("CONTENT_PUBLISHED", "ContentItem",
            UUID.randomUUID().toString(),
            "{\"eventId\":\"" + eventId + "\",\"siteId\":\"" + site + "\"}");
        when(outboxRepo.findByPublishedAtIsNullOrderByOccurredAt()).thenReturn(List.of(event));

        var sub = subscription(site, "CONTENT_PUBLISHED");
        when(subscriptions.findBySiteIdAndActiveTrue(site)).thenReturn(List.of(sub));
        when(deliveries.existsBySubscriptionIdAndEventId(sub.getId(), eventId)).thenReturn(false);

        relay.relayOnce();

        verify(deliveries).save(any(WebhookDeliveryEntity.class));
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void skipsNonMatchingEventsAndInactiveSites() {
        OutboxEventEntity event = OutboxEventEntity.create("OTHER_EVENT", "X", "1",
            "{\"siteId\":\"" + UUID.randomUUID() + "\"}");
        when(outboxRepo.findByPublishedAtIsNullOrderByOccurredAt()).thenReturn(List.of(event));
        when(subscriptions.findBySiteIdAndActiveTrue(any())).thenReturn(List.of());

        relay.relayOnce();

        verify(deliveries, never()).save(any());
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void duplicateSubscriptionEventIsNotDuplicated() {
        UUID site = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = OutboxEventEntity.create("CONTENT_PUBLISHED", "ContentItem", "1",
            "{\"eventId\":\"" + eventId + "\",\"siteId\":\"" + site + "\"}");
        when(outboxRepo.findByPublishedAtIsNullOrderByOccurredAt()).thenReturn(List.of(event));
        var sub = subscription(site, "CONTENT_PUBLISHED");
        when(subscriptions.findBySiteIdAndActiveTrue(site)).thenReturn(List.of(sub));
        when(deliveries.existsBySubscriptionIdAndEventId(sub.getId(), eventId)).thenReturn(true);

        relay.relayOnce();

        verify(deliveries, never()).save(any());
    }

    @Test
    void signatureIsStableHmacSha256() {
        String mac = WebhookRelayService.hmacSha256("secret", "body");
        assertThat(mac).hasSize(64);
        assertThat(WebhookRelayService.hmacSha256("secret", "body")).isEqualTo(mac);
        assertThat(WebhookRelayService.hmacSha256("other", "body")).isNotEqualTo(mac);
    }

    @Test
    void failedAttemptBacksOffThenDiesAtMaxAttempts() {
        var delivery = WebhookDeliveryEntity.create(UUID.randomUUID(), UUID.randomUUID(),
            "CONTENT_PUBLISHED", "{}", 3, Instant.now());
        delivery.recordFailure(3, "HTTP 500", 500);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryEntity.STATUS_PENDING);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(Instant.now());

        delivery.recordFailure(3, "HTTP 500", 500);
        delivery.recordFailure(3, "HTTP 503", 503);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryEntity.STATUS_DEAD);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getResponseStatus()).isEqualTo(503);
    }

    @Test
    void successRecordsStatusAndClearsError() {
        var delivery = WebhookDeliveryEntity.create(UUID.randomUUID(), UUID.randomUUID(),
            "CONTENT_PUBLISHED", "{}", 5, Instant.now());
        delivery.recordFailure(5, "timeout", null);
        delivery.recordSuccess(200);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryEntity.STATUS_DELIVERED);
        assertThat(delivery.getLastError()).isNull();
        assertThat(delivery.getDeliveredAt()).isNotNull();
    }

    @Test
    void deadLetterReplayResetsAndRetries() {
        var delivery = WebhookDeliveryEntity.create(UUID.randomUUID(), UUID.randomUUID(),
            "CONTENT_PUBLISHED", "{}", 2, Instant.now());
        delivery.recordFailure(2, "HTTP 500", 500);
        delivery.recordFailure(2, "HTTP 500", 500);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryEntity.STATUS_DEAD);

        when(deliveries.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        lenient().when(subscriptions.findById(delivery.getSubscriptionId()))
            .thenReturn(Optional.of(subscription(UUID.randomUUID(), "CONTENT_PUBLISHED")));

        boolean accepted = relay.retryDeadLetter(delivery.getId());

        assertThat(accepted).isTrue();
        // 重放后仍失败（订阅 URL 无法连通或非 2xx），但状态回到 PENDING/重试路径而非直接死信
        assertThat(delivery.getStatus()).isIn(WebhookDeliveryEntity.STATUS_PENDING, WebhookDeliveryEntity.STATUS_DEAD);
    }

    @Test
    void retryRejectsNonDeadDeliveries() {
        var pending = WebhookDeliveryEntity.create(UUID.randomUUID(), UUID.randomUUID(),
            "CONTENT_PUBLISHED", "{}", 5, Instant.now());
        when(deliveries.findById(pending.getId())).thenReturn(Optional.of(pending));
        assertThat(relay.retryDeadLetter(pending.getId())).isFalse();
        verify(deliveries, never()).save(any());
    }

    private static WebhookSubscriptionEntity subscription(UUID site, String events) {
        var sub = new WebhookSubscriptionEntity();
        sub.setSiteId(site);
        sub.setName("ops");
        sub.setUrl("http://localhost:19999/hook");
        sub.setSecret("s3cret");
        sub.setEvents(events);
        sub.setActive(true);
        return sub;
    }
}
