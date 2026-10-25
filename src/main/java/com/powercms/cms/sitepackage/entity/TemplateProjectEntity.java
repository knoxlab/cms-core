package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "template_project", uniqueConstraints = @UniqueConstraint(columnNames = {"site_id", "name"}))
public class TemplateProjectEntity {
    public static final String TYPE_REACT = "REACT";
    public static final String TYPE_VUE = "VUE";
    public static final String TYPE_FTL = "FTL";

    /** FR-AGT-TPL-008：模板功能类型——页面模板 */
    public static final String TPL_PAGE = "PAGE";
    /** FR-AGT-TPL-008：模板功能类型——组件模板 */
    public static final String TPL_COMPONENT = "COMPONENT";
    /** FR-AGT-TPL-001：模板功能类型——布局模板 */
    public static final String TPL_LAYOUT = "LAYOUT";

    @Id private UUID id;
    @Column(name = "site_id", nullable = false) private UUID siteId;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "project_type", nullable = false, length = 20) private String projectType = TYPE_REACT;
    /** FR-AGT-TPL-008：模板功能分类（PAGE/COMPONENT/LAYOUT） */
    @Column(name = "template_type", length = 20) private String templateType = TPL_PAGE;
    @Column(name = "runtime_image", length = 200) private String runtimeImage;
    @Column(name = "current_revision", nullable = false) private int currentRevision;
    @Column(name = "lockfile_hash", length = 128) private String lockfileHash;
    @Column(name = "package_json", columnDefinition = "text") private String packageJson;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); Instant n = Instant.now(); createdAt = n; updatedAt = n; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID v) { this.siteId = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public String getProjectType() { return projectType; } public void setProjectType(String v) { this.projectType = v; }
    public String getTemplateType() { return templateType; } public void setTemplateType(String v) { this.templateType = v; }
    public String getRuntimeImage() { return runtimeImage; } public void setRuntimeImage(String v) { this.runtimeImage = v; }
    public int getCurrentRevision() { return currentRevision; } public void setCurrentRevision(int v) { this.currentRevision = v; }
    public String getLockfileHash() { return lockfileHash; } public void setLockfileHash(String v) { this.lockfileHash = v; }
    public String getPackageJson() { return packageJson; } public void setPackageJson(String v) { this.packageJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
