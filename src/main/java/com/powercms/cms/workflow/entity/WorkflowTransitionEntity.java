package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "workflow_transition", uniqueConstraints = @UniqueConstraint(columnNames = {"workflow_definition_id", "source_status", "target_status", "allowed_role_id"}))
public class WorkflowTransitionEntity {

    @Id private UUID id;
    @Column(name = "workflow_definition_id", nullable = false)
    private UUID workflowDefinitionId;
    @Column(name = "source_status", nullable = false, length = 20)
    private String sourceStatus;
    @Column(name = "target_status", nullable = false, length = 20)
    private String targetStatus;
    @Column(name = "allowed_role_id")
    private UUID allowedRoleId;
    @Column(name = "required_comment", nullable = false)
    private boolean requiredComment;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(name = "min_approvers", nullable = false)
    private int minApprovers;
    @Column(name = "action_key", length = 64)
    private String actionKey;
    @Column(name = "action_name", length = 100)
    private String actionName;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getWorkflowDefinitionId() { return workflowDefinitionId; } public void setWorkflowDefinitionId(UUID v) { this.workflowDefinitionId = v; }
    public String getSourceStatus() { return sourceStatus; } public void setSourceStatus(String v) { this.sourceStatus = v; }
    public String getTargetStatus() { return targetStatus; } public void setTargetStatus(String v) { this.targetStatus = v; }
    public UUID getAllowedRoleId() { return allowedRoleId; } public void setAllowedRoleId(UUID v) { this.allowedRoleId = v; }
    public boolean isRequiredComment() { return requiredComment; } public void setRequiredComment(boolean v) { this.requiredComment = v; }
    public int getSortOrder() { return sortOrder; } public void setSortOrder(int v) { this.sortOrder = v; }
    public int getMinApprovers() { return minApprovers; } public void setMinApprovers(int v) { this.minApprovers = v; }
    public String getActionKey() { return actionKey; } public void setActionKey(String v) { this.actionKey = v; }
    public String getActionName() { return actionName; } public void setActionName(String v) { this.actionName = v; }
}
