package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="shared_component", uniqueConstraints=@UniqueConstraint(columnNames={"site_id","path"}))
public class SharedComponentEntity {
    @Id private UUID id;
    @Column(name="site_id",nullable=false) private UUID siteId;
    @Column(nullable=false,length=1000) private String path;
    @Column(nullable=false,length=500) private String title;
    @Column(name="content_type_id",nullable=false) private UUID contentTypeId;
    @Column(name="data_json",nullable=false,columnDefinition="text") private String dataJson="{}";
    @Column(name="template_path",length=500) private String templatePath;
    @Column(nullable=false,length=20) private String status="DRAFT";
    @Column(name="current_version",nullable=false) private int currentVersion;
    @Version @Column(name="lock_version",nullable=false) private long lockVersion;
    @Column(name="created_by",length=100) private String createdBy;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void create(){if(id==null)id=UUID.randomUUID();var now=Instant.now();createdAt=now;updatedAt=now;}
    @PreUpdate void update(){updatedAt=Instant.now();}
    public UUID getId(){return id;} public UUID getSiteId(){return siteId;} public void setSiteId(UUID v){siteId=v;}
    public String getPath(){return path;} public void setPath(String v){path=v;} public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public UUID getContentTypeId(){return contentTypeId;} public void setContentTypeId(UUID v){contentTypeId=v;}
    public String getDataJson(){return dataJson;} public void setDataJson(String v){dataJson=v;} public String getTemplatePath(){return templatePath;} public void setTemplatePath(String v){templatePath=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;} public int getCurrentVersion(){return currentVersion;} public void setCurrentVersion(int v){currentVersion=v;}
    public long getLockVersion(){return lockVersion;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
