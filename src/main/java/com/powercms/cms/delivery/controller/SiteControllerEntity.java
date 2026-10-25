package com.powercms.cms.delivery.controller;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_controller", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"site_id", "path"}),
    @UniqueConstraint(columnNames = {"site_id", "route_path", "http_method"})
})
public class SiteControllerEntity {
    @Id private UUID id;
    @Column(name="site_id", nullable=false) private UUID siteId;
    @Column(nullable=false, length=300) private String path;
    @Column(name="route_path", nullable=false, length=500) private String routePath;
    @Column(name="http_method", nullable=false, length=10) private String httpMethod;
    @Column(nullable=false, columnDefinition="text") private String definition;
    @Column(nullable=false, length=20) private String status = "DRAFT";
    @Column(name="current_version", nullable=false) private int currentVersion;
    @Column(name="published_version") private Integer publishedVersion;
    @Version @Column(name="lock_version", nullable=false) private long lockVersion;
    @Column(name="created_by", length=100) private String createdBy;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID(); Instant now=Instant.now(); createdAt=now; updatedAt=now; }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getSiteId(){return siteId;} public void setSiteId(UUID v){siteId=v;}
    public String getPath(){return path;} public void setPath(String v){path=v;}
    public String getRoutePath(){return routePath;} public void setRoutePath(String v){routePath=v;}
    public String getHttpMethod(){return httpMethod;} public void setHttpMethod(String v){httpMethod=v;}
    public String getDefinition(){return definition;} public void setDefinition(String v){definition=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public int getCurrentVersion(){return currentVersion;} public void setCurrentVersion(int v){currentVersion=v;}
    public Integer getPublishedVersion(){return publishedVersion;} public void setPublishedVersion(Integer v){publishedVersion=v;}
    public long getLockVersion(){return lockVersion;}
    public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
