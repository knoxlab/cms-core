package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_theme_revision", uniqueConstraints = @UniqueConstraint(columnNames = {"theme_id", "version"}))
public class SiteThemeRevisionEntity {
    @Id private UUID id;
    @Column(name = "theme_id", nullable = false) private UUID themeId;
    @Column(nullable = false) private int version;
    @Column(name = "parent_theme_id") private UUID parentThemeId;
    @Column(name = "parent_version") private Integer parentVersion;
    @Column(name = "parent_theme_version") private Integer parentThemeVersion;
    @Column(length = 255) private String author;
    @Column(length = 1000) private String message;
    @Column(name = "manifest_snapshot", nullable = false, columnDefinition = "text") private String manifestSnapshot;
    @Column(name = "file_digest", nullable = false, length = 64) private String fileDigest;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @PrePersist void create() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getThemeId(){return themeId;} public void setThemeId(UUID v){themeId=v;}
    public UUID getParentThemeId(){return parentThemeId;} public void setParentThemeId(UUID v){parentThemeId=v;} public Integer getParentThemeVersion(){return parentThemeVersion;} public void setParentThemeVersion(Integer v){parentThemeVersion=v;}
    public int getVersion(){return version;} public void setVersion(int v){version=v;} public Integer getParentVersion(){return parentVersion;} public void setParentVersion(Integer v){parentVersion=v;}
    public String getAuthor(){return author;} public void setAuthor(String v){author=v;} public String getMessage(){return message;} public void setMessage(String v){message=v;}
    public String getManifestSnapshot(){return manifestSnapshot;} public void setManifestSnapshot(String v){manifestSnapshot=v;} public String getFileDigest(){return fileDigest;} public void setFileDigest(String v){fileDigest=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}
