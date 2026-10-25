package com.powercms.cms.webhook.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Webhook 订阅（FR-API-007）：按站点订阅事件白名单，投递时以 secret 出 HMAC-SHA256 签名。
 */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscriptionEntity {

    public static final int MAX_EVENTS = 64;

    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(nullable = false, length = 200)
    private String secret;

    /** 逗号分隔事件类型白名单；空表示全部事件。 */
    @Column(nullable = false, length = 2000)
    private String events;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean accepts(String eventType) {
        if (events == null || events.isBlank()) return true;
        for (String token : events.split(",")) {
            if (eventType.equals(token.trim())) return true;
        }
        return false;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getSecret() { return secret; }
    public String getEvents() { return events; }
    public boolean isActive() { return active; }

    public void setSiteId(UUID v) { siteId = v; }
    public void setName(String v) { name = v; }
    public void setUrl(String v) { url = v; }
    public void setSecret(String v) { secret = v; }
    public void setEvents(String v) { events = v == null ? "" : v; }
    public void setActive(boolean v) { active = v; }
}
