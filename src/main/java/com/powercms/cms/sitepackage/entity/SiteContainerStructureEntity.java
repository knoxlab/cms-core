package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Holds the relationship between a container and one allowed content type plus its renderer. */
@Entity
@Table(name = "site_container_structure", uniqueConstraints = @UniqueConstraint(columnNames = {"container_id", "structure_id"}))
public class SiteContainerStructureEntity {
    @Id private UUID id;
    @Column(name = "container_id", nullable = false) private UUID containerId;
    @Column(name = "structure_id", nullable = false) private UUID structureId;
    @Column(nullable = false, columnDefinition = "text") private String code = "";
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void create() { if (id == null) id = UUID.randomUUID(); Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }
    public UUID getId() { return id; } public void setId(UUID value) { id = value; }
    public UUID getContainerId() { return containerId; } public void setContainerId(UUID value) { containerId = value; }
    public UUID getStructureId() { return structureId; } public void setStructureId(UUID value) { structureId = value; }
    public String getCode() { return code; } public void setCode(String value) { code = value; }
    public int getSortOrder() { return sortOrder; } public void setSortOrder(int value) { sortOrder = value; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
}
