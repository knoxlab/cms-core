package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_theme_share", uniqueConstraints = @UniqueConstraint(columnNames = {"theme_id", "consumer_site_id"}))
public class SiteThemeShareEntity {
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";
    @Id private UUID id;
    @Column(name="theme_id", nullable=false) private UUID themeId;
    @Column(name="consumer_site_id", nullable=false) private UUID consumerSiteId;
    @Column(name="pinned_version", nullable=false) private int pinnedVersion;
    @Column(nullable=false, length=20) private String status = ACTIVE;
    @Column(name="created_by", length=255) private String createdBy;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID(); Instant now=Instant.now(); createdAt=now; updatedAt=now; }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getThemeId(){return themeId;} public void setThemeId(UUID v){themeId=v;}
    public UUID getConsumerSiteId(){return consumerSiteId;} public void setConsumerSiteId(UUID v){consumerSiteId=v;}
    public int getPinnedVersion(){return pinnedVersion;} public void setPinnedVersion(int v){pinnedVersion=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
