package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_transition_log")
public class WorkflowTransitionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;
    @Column(name = "task_id")
    private UUID taskId;
    @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;
    @Column(name = "action_key", nullable = false, length = 64)
    private String actionKey;
    @Column(name = "from_status", nullable = false, length = 32)
    private String fromStatus;
    @Column(name = "to_status", nullable = false, length = 32)
    private String toStatus;
    @Column(nullable = false, length = 100)
    private String actor;
    @Column(length = 2000)
    private String comment;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static WorkflowTransitionLogEntity of(UUID siteId, UUID taskId, UUID contentItemId,
                                                 String actionKey, String fromStatus, String toStatus,
                                                 String actor, String comment) {
        var log = new WorkflowTransitionLogEntity();
        log.siteId = siteId;
        log.taskId = taskId;
        log.contentItemId = contentItemId;
        log.actionKey = actionKey;
        log.fromStatus = fromStatus;
        log.toStatus = toStatus;
        log.actor = actor;
        log.comment = comment;
        log.createdAt = Instant.now();
        return log;
    }

    public Long getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public UUID getTaskId() { return taskId; }
    public UUID getContentItemId() { return contentItemId; }
    public String getActionKey() { return actionKey; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}
