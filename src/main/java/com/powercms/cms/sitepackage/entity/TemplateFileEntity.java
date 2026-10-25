package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "template_file", uniqueConstraints = @UniqueConstraint(columnNames = {"template_project_id", "path"}))
public class TemplateFileEntity {

    @Id private UUID id;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "template_project_id", nullable = false) private UUID templateProjectId;
    @Column(nullable = false, length = 500) private String path;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @Column(name = "file_hash", length = 128) private String fileHash;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getTemplateProjectId() { return templateProjectId; } public void setTemplateProjectId(UUID v) { this.templateProjectId = v; }
    public String getPath() { return path; } public void setPath(String v) { this.path = v; }
    public String getContent() { return content; } public void setContent(String v) { this.content = v; }
    public String getFileHash() { return fileHash; } public void setFileHash(String v) { this.fileHash = v; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
