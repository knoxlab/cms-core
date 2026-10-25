package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "site_template_acl",
    uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "role_code"}))
public class SiteTemplateAclEntity {
    @Id private UUID id;
    @Column(name = "template_id", nullable = false) private UUID templateId;
    @Column(name = "role_code", nullable = false, length = 50) private String roleCode;
    @Column(name = "can_view", nullable = false) private boolean canView = true;
    @Column(name = "can_use", nullable = false) private boolean canUse;
    @Column(name = "can_edit", nullable = false) private boolean canEdit;
    @Column(name = "can_publish", nullable = false) private boolean canPublish;

    @PrePersist void create() { if (id == null) id = UUID.randomUUID(); }

    public UUID getId() { return id; }
    public void setId(UUID v) { id = v; }
    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID v) { templateId = v; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String v) { roleCode = v; }
    public boolean isCanView() { return canView; }
    public void setCanView(boolean v) { canView = v; }
    public boolean isCanUse() { return canUse; }
    public void setCanUse(boolean v) { canUse = v; }
    public boolean isCanEdit() { return canEdit; }
    public void setCanEdit(boolean v) { canEdit = v; }
    public boolean isCanPublish() { return canPublish; }
    public void setCanPublish(boolean v) { canPublish = v; }
}
