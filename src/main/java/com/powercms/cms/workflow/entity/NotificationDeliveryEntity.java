package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notification_delivery")
public class NotificationDeliveryEntity {

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String SENT = "SENT";
    public static final String SKIPPED_DUPLICATE = "SKIPPED_DUPLICATE";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "notification_id")
    private java.util.UUID notificationId;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;
    @Column(nullable = false, length = 16)
    private String channel;
    @Column(nullable = false, length = 200)
    private String recipient;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "last_error", length = 2000)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public static NotificationDeliveryEntity of(String eventType, String idempotencyKey, String channel,
                                                String recipient, String status, String lastError) {
        var d = new NotificationDeliveryEntity();
        d.eventType = eventType;
        d.idempotencyKey = idempotencyKey;
        d.channel = channel;
        d.recipient = recipient;
        d.status = status;
        d.attempts = 1;
        d.lastError = lastError;
        Instant now = Instant.now();
        d.createdAt = now;
        d.updatedAt = now;
        return d;
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
}
