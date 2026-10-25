package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_task")
public class WorkflowTaskEntity {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_DELEGATED = "DELEGATED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String EXEC_QUEUED = "QUEUED";
    public static final String EXEC_PROCESSING = "PROCESSING";
    public static final String EXEC_SUCCEEDED = "SUCCEEDED";
    public static final String EXEC_FAILED = "FAILED";

    @Id private UUID id;
    @Column(name = "site_id")
    private UUID siteId;
    @Column(name = "workflow_def_id", nullable = false)
    private UUID workflowDefId;
    @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;
    @Column(name = "content_version", nullable = false)
    private int contentVersion;
    @Column(name = "source_status", nullable = false, length = 20)
    private String sourceStatus;
    @Column(name = "target_status", nullable = false, length = 20)
    private String targetStatus;
    @Column(nullable = false, length = 100)
    private String assignee;
    @Column(length = 100)
    private String submitter;
    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;
    @Column(length = 2000)
    private String comment;
    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "scheduled_at")
    private Instant scheduledAt;
    @Column(name = "execution_state", length = 24)
    private String executionState;
    @Column(name = "delegated_from", length = 100)
    private String delegatedFrom;
    @Column(name = "last_timeout_warned_at")
    private Instant lastTimeoutWarnedAt;
    @Column(name = "timeout_escalated_at")
    private Instant timeoutEscalatedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static WorkflowTaskEntity create(UUID wfDefId, UUID itemId, int version,
                                              String source, String target, String assignee) {
        return create(null, wfDefId, itemId, version, source, target, assignee, null);
    }

    public static WorkflowTaskEntity create(UUID siteId, UUID wfDefId, UUID itemId, int version,
                                              String source, String target, String assignee, String submitter) {
        var t = new WorkflowTaskEntity();
        t.id = UUID.randomUUID();
        t.siteId = siteId;
        t.workflowDefId = wfDefId;
        t.contentItemId = itemId;
        t.contentVersion = version;
        t.sourceStatus = source;
        t.targetStatus = target;
        t.assignee = assignee;
        t.submitter = submitter;
        t.createdAt = Instant.now();
        return t;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public UUID getWorkflowDefId() { return workflowDefId; }
    public UUID getContentItemId() { return contentItemId; }
    public int getContentVersion() { return contentVersion; }
    public String getSourceStatus() { return sourceStatus; }
    public String getTargetStatus() { return targetStatus; }
    /** 定时发布场景：审批通过时把「提交审核型」任务的终态固化为实际推进目标，供执行器落库。 */
    public void setTargetStatus(String v) { this.targetStatus = v; }
    public String getAssignee() { return assignee; } public void setAssignee(String v) { this.assignee = v; }
    public String getSubmitter() { return submitter; } public void setSubmitter(String v) { this.submitter = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getComment() { return comment; } public void setComment(String v) { this.comment = v; }
    public String getResolvedBy() { return resolvedBy; } public void setResolvedBy(String v) { this.resolvedBy = v; }
    public Instant getResolvedAt() { return resolvedAt; } public void setResolvedAt(Instant v) { this.resolvedAt = v; }
    public Instant getScheduledAt() { return scheduledAt; } public void setScheduledAt(Instant v) { this.scheduledAt = v; }
    public String getExecutionState() { return executionState; } public void setExecutionState(String v) { this.executionState = v; }
    public String getDelegatedFrom() { return delegatedFrom; } public void setDelegatedFrom(String v) { this.delegatedFrom = v; }
    public Instant getLastTimeoutWarnedAt() { return lastTimeoutWarnedAt; } public void setLastTimeoutWarnedAt(Instant v) { this.lastTimeoutWarnedAt = v; }
    public Instant getTimeoutEscalatedAt() { return timeoutEscalatedAt; } public void setTimeoutEscalatedAt(Instant v) { this.timeoutEscalatedAt = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { this.createdAt = v; }
}
