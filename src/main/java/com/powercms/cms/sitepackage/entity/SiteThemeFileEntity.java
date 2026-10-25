package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_theme_file", uniqueConstraints = @UniqueConstraint(columnNames = {"theme_id", "path"}))
public class SiteThemeFileEntity {
    @Id private UUID id;
    @Column(name = "theme_id", nullable = false) private UUID themeId;
    @Column(nullable = false, length = 1000) private String path;
    @Column(name = "media_type", nullable = false, length = 255) private String mediaType;
    @Column(name = "file_size", nullable = false) private long size;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(nullable = false, columnDefinition = "bytea") private byte[] content;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @PrePersist void create() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId() { return id; } public void setId(UUID v) { id = v; }
    public UUID getThemeId() { return themeId; } public void setThemeId(UUID v) { themeId = v; }
    public String getPath() { return path; } public void setPath(String v) { path = v; }
    public String getMediaType() { return mediaType; } public void setMediaType(String v) { mediaType = v; }
    public long getSize() { return size; } public void setSize(long v) { size = v; }
    public String getSha256() { return sha256; } public void setSha256(String v) { sha256 = v; }
    public byte[] getContent() { return content; } public void setContent(byte[] v) { content = v; }
    public Instant getCreatedAt() { return createdAt; }
}
