package com.powercms.cms.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计事件：追加写，普通管理员不得修改或删除（FR-AUDIT-001～003）。
 * detail 存储安全的变化摘要（jsonb），敏感值必须脱敏。
 */
@Entity
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 200)
    private String actor;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "object_type", nullable = false, length = 100)
    private String objectType;

    @Column(name = "object_id", length = 200)
    private String objectId;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    public static AuditEventEntity create(String actor, String action, String objectType,
                                           String objectId, UUID siteId, String result,
                                           String traceId, String detail) {
        AuditEventEntity e = new AuditEventEntity();
        e.id = UUID.randomUUID();
        e.occurredAt = Instant.now();
        e.actor = actor;
        e.action = action;
        e.objectType = objectType;
        e.objectId = objectId;
        e.siteId = siteId;
        e.result = result;
        e.traceId = traceId;
        e.detail = detail;
        return e;
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getObjectType() { return objectType; }
    public String getObjectId() { return objectId; }
    public UUID getSiteId() { return siteId; }
    public String getResult() { return result; }
    public String getTraceId() { return traceId; }
    public String getDetail() { return detail; }
}
