package com.powercms.cms.publish.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "release_theme_version", uniqueConstraints = @UniqueConstraint(columnNames = {"release_id", "theme_revision_id"}))
public class ReleaseThemeVersionEntity {
    @Id private UUID id;
    @Column(name = "release_id", nullable = false) private UUID releaseId;
    @Column(name = "theme_revision_id", nullable = false) private UUID themeRevisionId;
    @Column(name = "theme_id", nullable = false) private UUID themeId;
    @Column(name = "theme_version", nullable = false) private int themeVersion;
    @Column(name = "owner_site_id", nullable = false) private UUID ownerSiteId;
    @Column(nullable = false, length = 20) private String source;
    @Column(name = "reference_role", nullable = false, length = 20) private String referenceRole;
    @Column(name = "file_digest", nullable = false, length = 64) private String fileDigest;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @PrePersist void create() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getReleaseId(){return releaseId;} public void setReleaseId(UUID v){releaseId=v;}
    public UUID getThemeRevisionId(){return themeRevisionId;} public void setThemeRevisionId(UUID v){themeRevisionId=v;}
    public UUID getThemeId(){return themeId;} public void setThemeId(UUID v){themeId=v;} public int getThemeVersion(){return themeVersion;} public void setThemeVersion(int v){themeVersion=v;}
    public UUID getOwnerSiteId(){return ownerSiteId;} public void setOwnerSiteId(UUID v){ownerSiteId=v;} public String getSource(){return source;} public void setSource(String v){source=v;}
    public String getReferenceRole(){return referenceRole;} public void setReferenceRole(String v){referenceRole=v;}
    public String getFileDigest(){return fileDigest;} public void setFileDigest(String v){fileDigest=v;} public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}
