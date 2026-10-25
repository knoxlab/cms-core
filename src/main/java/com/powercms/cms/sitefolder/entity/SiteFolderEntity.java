package com.powercms.cms.sitefolder.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="site_folder", uniqueConstraints={@UniqueConstraint(columnNames={"site_id","path"})})
public class SiteFolderEntity {
 @Id private UUID id; @Column(name="site_id",nullable=false) private UUID siteId; @Column(name="parent_id") private UUID parentId;
 @Column(nullable=false,length=200) private String title; @Column(nullable=false,length=120) private String name; @Column(nullable=false,length=1000) private String path;
 @Column(name="sort_order",nullable=false) private int sortOrder; @Column(name="show_on_menu",nullable=false) private boolean showOnMenu=true;
 @Column(name="allowed_file_extensions",length=1000) private String allowedFileExtensions; @Column(name="default_file_asset_type",nullable=false,length=50) private String defaultFileAssetType="FILE";
 @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
 @PrePersist void create(){if(id==null)id=UUID.randomUUID();Instant now=Instant.now();if(createdAt==null)createdAt=now;updatedAt=now;} @PreUpdate void update(){updatedAt=Instant.now();}
 public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getSiteId(){return siteId;} public void setSiteId(UUID v){siteId=v;} public UUID getParentId(){return parentId;} public void setParentId(UUID v){parentId=v;}
 public String getTitle(){return title;} public void setTitle(String v){title=v;} public String getName(){return name;} public void setName(String v){name=v;} public String getPath(){return path;} public void setPath(String v){path=v;}
 public int getSortOrder(){return sortOrder;} public void setSortOrder(int v){sortOrder=v;} public boolean isShowOnMenu(){return showOnMenu;} public void setShowOnMenu(boolean v){showOnMenu=v;}
 public String getAllowedFileExtensions(){return allowedFileExtensions;} public void setAllowedFileExtensions(String v){allowedFileExtensions=v;} public String getDefaultFileAssetType(){return defaultFileAssetType;} public void setDefaultFileAssetType(String v){defaultFileAssetType=v;}
 public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
