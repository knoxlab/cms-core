package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "site_theme_revision_file", uniqueConstraints = @UniqueConstraint(columnNames = {"theme_revision_id", "path"}))
public class SiteThemeRevisionFileEntity {
    @Id private UUID id;
    @Column(name = "theme_revision_id", nullable = false) private UUID themeRevisionId;
    @Column(nullable = false, length = 1000) private String path;
    @Column(name = "media_type", nullable = false, length = 255) private String mediaType;
    @Column(name = "file_size", nullable = false) private long size;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(nullable = false, columnDefinition = "bytea") private byte[] content;
    @PrePersist void create(){if(id==null)id=UUID.randomUUID();}
    public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getThemeRevisionId(){return themeRevisionId;} public void setThemeRevisionId(UUID v){themeRevisionId=v;}
    public String getPath(){return path;} public void setPath(String v){path=v;} public String getMediaType(){return mediaType;} public void setMediaType(String v){mediaType=v;}
    public long getSize(){return size;} public void setSize(long v){size=v;} public String getSha256(){return sha256;} public void setSha256(String v){sha256=v;}
    public byte[] getContent(){return content;} public void setContent(byte[] v){content=v;}
}
