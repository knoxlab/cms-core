package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_definition")
public class WorkflowDefinitionEntity {
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DEPRECATED = "DEPRECATED";
    public static final String SYSTEM_NAME = "系统工作流";

    @Id private UUID id;
    @Column(name = "site_id", nullable = false)
    private UUID siteId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 1000)
    private String description;
    @Column(nullable = false) private int version = 1;
    @Column(nullable = false, length = 20)
    private String status = STATUS_DRAFT;
    @Column(name = "is_system", nullable = false)
    private boolean system;
    @Column(name = "timeout_minutes", nullable = false)
    private int timeoutMinutes;
    @Column(name = "reminder_interval_minutes", nullable = false)
    private int reminderIntervalMinutes;
    @Column(name = "timeout_action_key", nullable = false, length = 32)
    private String timeoutActionKey = "NOTIFY";
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); Instant n = Instant.now(); createdAt = n; updatedAt = n; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getDescription() { return description; } public void setDescription(String description) { this.description = description; }
    public int getVersion() { return version; } public void setVersion(int version) { this.version = version; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public boolean isSystem() { return system; } public void setSystem(boolean system) { this.system = system; }
    public int getTimeoutMinutes() { return timeoutMinutes; } public void setTimeoutMinutes(int v) { this.timeoutMinutes = v; }
    public int getReminderIntervalMinutes() { return reminderIntervalMinutes; } public void setReminderIntervalMinutes(int v) { this.reminderIntervalMinutes = v; }
    public String getTimeoutActionKey() { return timeoutActionKey; } public void setTimeoutActionKey(String v) { this.timeoutActionKey = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
