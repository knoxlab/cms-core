package com.powercms.cms.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "category")
public class CategoryEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";

    @Id private UUID id;
    @Column(name = "site_id", nullable = false)
    private UUID siteId;
    @Column(name = "parent_id")
    private UUID parentId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "path_alias", length = 400)
    private String pathAlias;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(nullable = false)
    private int depth;
    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); Instant n = Instant.now(); createdAt = n; updatedAt = n; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getParentId() { return parentId; } public void setParentId(UUID parentId) { this.parentId = parentId; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getPathAlias() { return pathAlias; } public void setPathAlias(String pathAlias) { this.pathAlias = pathAlias; }
    public int getSortOrder() { return sortOrder; } public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public int getDepth() { return depth; } public void setDepth(int depth) { this.depth = depth; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
