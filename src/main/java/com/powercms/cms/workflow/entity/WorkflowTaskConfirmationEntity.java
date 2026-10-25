package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_task_confirmation")
public class WorkflowTaskConfirmationEntity {

    public static final String ACTION_APPROVE = "approve";

    @Id private UUID id;
    @Column(name = "task_id", nullable = false)
    private UUID taskId;
    @Column(name = "action_key", nullable = false, length = 64)
    private String actionKey;
    @Column(nullable = false, length = 100)
    private String actor;
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    public static WorkflowTaskConfirmationEntity of(UUID taskId, String actionKey, String actor) {
        var e = new WorkflowTaskConfirmationEntity();
        e.id = UUID.randomUUID();
        e.taskId = taskId;
        e.actionKey = actionKey;
        e.actor = actor;
        e.confirmedAt = Instant.now();
        return e;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public String getActionKey() { return actionKey; }
    public String getActor() { return actor; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
