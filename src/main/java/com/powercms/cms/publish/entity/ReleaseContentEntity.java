package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "release_content")
@IdClass(ReleaseContentEntity.PK.class)
public class ReleaseContentEntity {

    @Id @Column(name = "release_id", nullable = false)
    private UUID releaseId;
    @Id @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;
    @Column(nullable = false)
    private int version;

    public static class PK implements Serializable {
        private UUID releaseId;
        private UUID contentItemId;
        public PK() {}
        public PK(UUID releaseId, UUID contentItemId) { this.releaseId = releaseId; this.contentItemId = contentItemId; }
        @Override public boolean equals(Object o) { if (!(o instanceof PK pk)) return false; return Objects.equals(releaseId, pk.releaseId) && Objects.equals(contentItemId, pk.contentItemId); }
        @Override public int hashCode() { return Objects.hash(releaseId, contentItemId); }
    }

    public UUID getReleaseId() { return releaseId; } public void setReleaseId(UUID v) { this.releaseId = v; }
    public UUID getContentItemId() { return contentItemId; } public void setContentItemId(UUID v) { this.contentItemId = v; }
    public int getVersion() { return version; } public void setVersion(int v) { this.version = v; }
}
