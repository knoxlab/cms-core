package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_template_revision",
    uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "version"}))
public class SiteTemplateRevisionEntity {
    @Id private UUID id;
    @Column(name = "template_id", nullable = false) private UUID templateId;
    @Column(nullable = false) private int version;
    @Column(name = "parent_version") private Integer parentVersion;
    @Column(length = 100) private String author;
    @Column(length = 500) private String message;
    @Column(nullable = false, columnDefinition = "text") private String snapshot;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public static SiteTemplateRevisionEntity create(UUID templateId, int version, Integer parentVersion,
                                                    String author, String message, String snapshot) {
        var entity = new SiteTemplateRevisionEntity();
        entity.id = UUID.randomUUID();
        entity.templateId = templateId;
        entity.version = version;
        entity.parentVersion = parentVersion;
        entity.author = author;
        entity.message = message;
        entity.snapshot = snapshot;
        entity.createdAt = Instant.now();
        return entity;
    }

    public UUID getId() { return id; }
    public UUID getTemplateId() { return templateId; }
    public int getVersion() { return version; }
    public Integer getParentVersion() { return parentVersion; }
    public String getAuthor() { return author; }
    public String getMessage() { return message; }
    public String getSnapshot() { return snapshot; }
    public Instant getCreatedAt() { return createdAt; }
}
