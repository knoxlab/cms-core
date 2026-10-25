package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="site_container_revision", uniqueConstraints=@UniqueConstraint(columnNames={"container_id","version"}))
public class SiteContainerRevisionEntity {
    @Id private UUID id;
    @Column(name="container_id",nullable=false) private UUID containerId;
    @Column(nullable=false) private int version;
    @Column(name="parent_version") private Integer parentVersion;
    @Column(length=100) private String author;
    @Column(length=500) private String message;
    @Column(nullable=false,columnDefinition="text") private String snapshot;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    public static SiteContainerRevisionEntity create(UUID containerId,int version,Integer parentVersion,String author,String message,String snapshot){var e=new SiteContainerRevisionEntity();e.id=UUID.randomUUID();e.containerId=containerId;e.version=version;e.parentVersion=parentVersion;e.author=author;e.message=message;e.snapshot=snapshot;e.createdAt=Instant.now();return e;}
    public UUID getId(){return id;} public UUID getContainerId(){return containerId;} public int getVersion(){return version;} public Integer getParentVersion(){return parentVersion;} public String getAuthor(){return author;} public String getMessage(){return message;} public String getSnapshot(){return snapshot;} public Instant getCreatedAt(){return createdAt;}
}
