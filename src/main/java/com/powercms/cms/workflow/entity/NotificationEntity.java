package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class NotificationEntity {

    @Id private UUID id;
    @Column(nullable = false, length = 100)
    private String recipient;
    @Column(name = "group_id")
    private UUID groupId;
    @Column(nullable = false, length = 50)
    private String type;
    @Column(length = 8)
    private String level = "INFO";
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 2000)
    private String body;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "related_type", length = 50)
    private String relatedType;
    @Column(name = "related_id")
    private UUID relatedId;
    @Column(length = 500)
    private String link;
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static NotificationEntity create(String recipient, String type, String title,
                                              String body, String relatedType, UUID relatedId) {
        var n = new NotificationEntity();
        n.id = UUID.randomUUID();
        n.recipient = recipient;
        n.type = type;
        n.title = title;
        n.body = body;
        n.relatedType = relatedType;
        n.relatedId = relatedId;
        n.groupId = relatedId;
        n.level = "INFO";
        n.createdAt = Instant.now();
        return n;
    }

    public UUID getId() { return id; }
    public String getRecipient() { return recipient; }
    public UUID getGroupId() { return groupId; } public void setGroupId(UUID groupId) { this.groupId = groupId; }
    public String getType() { return type; }
    public String getLevel() { return level; } public void setLevel(String level) { this.level = level; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Instant getReadAt() { return readAt; } public void setReadAt(Instant v) { this.readAt = v; }
    public String getRelatedType() { return relatedType; }
    public UUID getRelatedId() { return relatedId; }
    public String getLink() { return link; } public void setLink(String link) { this.link = link; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
