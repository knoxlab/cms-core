package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="site_theme", uniqueConstraints=@UniqueConstraint(columnNames={"site_id","name"}))
public class SiteThemeEntity {
    @Id private UUID id;
    @Column(name="site_id",nullable=false) private UUID siteId;
    @Column(nullable=false,length=200) private String name;
    @Column(length=1000) private String description;
    @Column(nullable=false,columnDefinition="text") private String config="{}";
    @Column(nullable=false,length=20) private String status="DRAFT";
    @Column(name="manifest_version",length=100) private String manifestVersion;
    @Column(length=200) private String vendor;
    @Column(name="cms_version_range",length=100) private String cmsVersionRange;
    @Column(nullable=false,length=20) private String validity="LEGACY";
    @Column(nullable=false,columnDefinition="text") private String warnings="[]";
    @Column(name="current_version",nullable=false) private int currentVersion;
    @Column(name="parent_theme_id") private UUID parentThemeId;
    @Column(name="parent_version") private Integer parentVersion;
    @Version @Column(name="lock_version",nullable=false) private long lockVersion;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void create(){if(id==null)id=UUID.randomUUID();var n=Instant.now();createdAt=n;updatedAt=n;}
    @PreUpdate void update(){updatedAt=Instant.now();}
    public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getSiteId(){return siteId;} public void setSiteId(UUID v){siteId=v;}
    public String getName(){return name;} public void setName(String v){name=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getConfig(){return config;} public void setConfig(String v){config=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getManifestVersion(){return manifestVersion;} public void setManifestVersion(String v){manifestVersion=v;} public String getVendor(){return vendor;} public void setVendor(String v){vendor=v;}
    public String getCmsVersionRange(){return cmsVersionRange;} public void setCmsVersionRange(String v){cmsVersionRange=v;} public String getValidity(){return validity;} public void setValidity(String v){validity=v;}
    public String getWarnings(){return warnings;} public void setWarnings(String v){warnings=v;} public int getCurrentVersion(){return currentVersion;} public void setCurrentVersion(int v){currentVersion=v;}
    public UUID getParentThemeId(){return parentThemeId;} public void setParentThemeId(UUID v){parentThemeId=v;} public Integer getParentVersion(){return parentVersion;} public void setParentVersion(Integer v){parentVersion=v;}
    public long getLockVersion(){return lockVersion;} public void setLockVersion(long v){lockVersion=v;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
