package com.powercms.cms.media.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 站点媒体目录（FR-AGT-MEDIA-005）：path 为物化路径（形如 {@code /images/2026}），
 * 与 {@code MediaAssetEntity.folderPath} 同口径字符串，资产仍以字符串挂载以保持既有读取零改动。
 *
 * <p>目录级 ACL 未建模，创建/搬移复用站点 {@code write} 能力；目录树深度由 path 段数推导。
 */
@Entity
@Table(name = "media_folder")
public class MediaFolderEntity {

    @Id private UUID id;
    @Column(name = "site_id", nullable = false)
    private UUID siteId;
    @Column(name = "parent_id")
    private UUID parentId;
    @Column(nullable = false, length = 150)
    private String name;
    @Column(nullable = false, length = 500)
    private String path;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
