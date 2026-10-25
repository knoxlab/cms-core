package com.powercms.cms.delivery.controller;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="site_controller_version", uniqueConstraints=@UniqueConstraint(columnNames={"controller_id","version"}))
public class SiteControllerVersionEntity {
    @Id private UUID id;
    @Column(name="controller_id", nullable=false) private UUID controllerId;
    @Column(nullable=false) private int version;
    @Column(nullable=false, columnDefinition="text") private String definition;
    @Column(name="definition_digest", nullable=false, length=64) private String definitionDigest;
    @Column(length=100) private String author;
    @Column(length=500) private String message;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    public static SiteControllerVersionEntity create(UUID controllerId,int version,String definition,String digest,String author,String message){ var e=new SiteControllerVersionEntity(); e.id=UUID.randomUUID();e.controllerId=controllerId;e.version=version;e.definition=definition;e.definitionDigest=digest;e.author=author;e.message=message;e.createdAt=Instant.now();return e; }
    public UUID getId(){return id;} public UUID getControllerId(){return controllerId;} public int getVersion(){return version;} public String getDefinition(){return definition;} public String getDefinitionDigest(){return definitionDigest;} public String getAuthor(){return author;} public String getMessage(){return message;} public Instant getCreatedAt(){return createdAt;}
}
