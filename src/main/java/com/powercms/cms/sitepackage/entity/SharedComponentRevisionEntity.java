package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="shared_component_revision",uniqueConstraints=@UniqueConstraint(columnNames={"component_id","version"}))
public class SharedComponentRevisionEntity {
    @Id private UUID id; @Column(name="component_id",nullable=false) private UUID componentId; @Column(nullable=false) private int version;
    @Column(name="parent_version") private Integer parentVersion; @Column(nullable=false,columnDefinition="text") private String snapshot;
    @Column(length=100) private String author; @Column(length=500) private String message; @Column(name="created_at",nullable=false) private Instant createdAt;
    public static SharedComponentRevisionEntity create(UUID componentId,int version,Integer parent,String snapshot,String author,String message){var e=new SharedComponentRevisionEntity();e.id=UUID.randomUUID();e.componentId=componentId;e.version=version;e.parentVersion=parent;e.snapshot=snapshot;e.author=author;e.message=message;e.createdAt=Instant.now();return e;}
    public UUID getId(){return id;} public UUID getComponentId(){return componentId;} public int getVersion(){return version;} public Integer getParentVersion(){return parentVersion;}
    public String getSnapshot(){return snapshot;} public String getAuthor(){return author;} public String getMessage(){return message;} public Instant getCreatedAt(){return createdAt;}
}
