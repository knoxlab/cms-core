package com.powercms.cms.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 事务 Outbox：业务事件至少一次投递，消费者幂等（规格书 9.2）。
 * 消费者使用 eventId 或业务幂等键去重。
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 200)
    private String aggregateId;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    public static OutboxEventEntity create(String eventType, String aggregateType,
                                            String aggregateId, String payload) {
        OutboxEventEntity e = new OutboxEventEntity();
        e.id = UUID.randomUUID();
        e.eventType = eventType;
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.payload = payload;
        e.occurredAt = Instant.now();
        e.attempts = 0;
        return e;
    }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
