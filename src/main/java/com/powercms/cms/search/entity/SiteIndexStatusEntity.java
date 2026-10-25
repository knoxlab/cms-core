package com.powercms.cms.search.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 访客索引状态登记（FR-SEARCH-007）：每站点一行，部署门禁据此判断索引是否与目标 Release 一致。
 */
@Entity
@Table(name = "site_index_status")
public class SiteIndexStatusEntity {

    @Id
    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "index_alias", nullable = false, length = 200)
    private String indexAlias;

    @Column(name = "last_release_id", nullable = false)
    private UUID lastReleaseId;

    @Column(name = "document_count", nullable = false)
    private long documentCount;

    @Column(name = "rebuilt_at", nullable = false)
    private Instant rebuiltAt;

    public static SiteIndexStatusEntity of(UUID siteId, String indexAlias, UUID releaseId, long documentCount) {
        SiteIndexStatusEntity e = new SiteIndexStatusEntity();
        e.siteId = siteId;
        e.indexAlias = indexAlias;
        e.lastReleaseId = releaseId;
        e.documentCount = documentCount;
        e.rebuiltAt = Instant.now();
        return e;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        if (rebuiltAt == null) rebuiltAt = Instant.now();
    }

    public UUID getSiteId() { return siteId; }
    public String getIndexAlias() { return indexAlias; }
    public UUID getLastReleaseId() { return lastReleaseId; }
    public long getDocumentCount() { return documentCount; }
    public Instant getRebuiltAt() { return rebuiltAt; }

    public void setIndexAlias(String v) { indexAlias = v; }
    public void setLastReleaseId(UUID v) { lastReleaseId = v; }
    public void setDocumentCount(long v) { documentCount = v; }
    public void setRebuiltAt(Instant v) { rebuiltAt = v; }
}
