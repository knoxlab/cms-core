package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "template_revision", uniqueConstraints = @UniqueConstraint(columnNames = {"template_project_id", "version"}))
public class TemplateRevisionEntity {

    @Id private UUID id;
    @Column(name = "template_project_id", nullable = false) private UUID templateProjectId;
    @Column(nullable = false) private int version;
    @Column(name = "parent_version") private Integer parentVersion;
    @Column(length = 100) private String author;
    @Column(length = 500) private String message;
    @Column(name = "file_snapshot", nullable = false, columnDefinition = "text") private String fileSnapshot;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public static TemplateRevisionEntity create(UUID projectId, int version, Integer parentVersion,
                                                  String author, String message, String snapshot) {
        var r = new TemplateRevisionEntity();
        r.id = UUID.randomUUID();
        r.templateProjectId = projectId;
        r.version = version;
        r.parentVersion = parentVersion;
        r.author = author;
        r.message = message;
        r.fileSnapshot = snapshot;
        r.createdAt = Instant.now();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getTemplateProjectId() { return templateProjectId; }
    public int getVersion() { return version; }
    public Integer getParentVersion() { return parentVersion; }
    public String getAuthor() { return author; }
    public String getMessage() { return message; }
    public String getFileSnapshot() { return fileSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
}
