package com.powercms.cms.webhook.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 单次事件对单个订阅的一次投递记录（FR-API-007）。
 * status: PENDING / DELIVERED / DEAD；(subscription_id, event_id) 唯一，消费端可按 eventId 幂等去重。
 */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDeliveryEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_DEAD = "DEAD";

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    public static WebhookDeliveryEntity create(UUID subscriptionId, UUID eventId, String eventType,
                                               String payload, int maxAttempts, Instant now) {
        WebhookDeliveryEntity d = new WebhookDeliveryEntity();
        d.id = UUID.randomUUID();
        d.subscriptionId = subscriptionId;
        d.eventId = eventId;
        d.eventType = eventType;
        d.payload = payload;
        d.maxAttempts = maxAttempts;
        d.status = STATUS_PENDING;
        d.nextAttemptAt = now;
        d.createdAt = now;
        return d;
    }

    /** 指数退避：2^n 秒，封顶 5 分钟。 */
    public Instant backoffDelay(int attempt) {
        long seconds = Math.min(1L << Math.min(attempt, 8), 300);
        return Instant.now().plusSeconds(seconds);
    }

    /** 立即到期（手动重试入口）。 */
    public void markDueNow() {
        this.nextAttemptAt = Instant.now();
    }

    /** 死信重放入口。 */
    public void retryAsPending() {
        this.status = STATUS_PENDING;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
        this.lastError = null;
    }

    public void recordFailure(int maxRetries, String error, Integer responseStatus) {
        this.attempts++;
        this.lastError = truncate(error);
        this.responseStatus = responseStatus;
        if (this.attempts >= this.maxAttempts) {
            this.status = STATUS_DEAD;
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt = backoffDelay(this.attempts);
        }
    }

    public void recordSuccess(int statusCode) {
        this.attempts++;
        this.status = STATUS_DELIVERED;
        this.responseStatus = statusCode;
        this.deliveredAt = Instant.now();
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public UUID getId() { return id; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }

    public void setMaxAttempts(int v) { maxAttempts = v; }
}
