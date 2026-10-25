package com.powercms.cms.media.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_version", uniqueConstraints = @UniqueConstraint(columnNames = {"media_asset_id", "version"}))
public class MediaVersionEntity {

    @Id private UUID id;
    @Column(name = "media_asset_id", nullable = false)
    private UUID mediaAssetId;
    @Column(nullable = false) private int version;
    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;
    @Column(nullable = false, length = 500) private String filename;
    @Column(name = "mime_type", length = 100) private String mimeType;
    @Column(name = "file_size", nullable = false) private long fileSize;
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;
    @Column(name = "stored_at", nullable = false)
    private Instant storedAt = Instant.now();

    /** FR-KNX-023 该版本的处理产物快照（播放计划/档位/海报等 JSON）；替换出新版本时旧版本产物保留。 */
    @Column(name = "artifacts_json", columnDefinition = "text")
    private String artifactsJson;

    public static MediaVersionEntity create(UUID mediaAssetId, int version, String contentHash,
                                             String filename, String mimeType, long fileSize, String storageKey) {
        MediaVersionEntity v = new MediaVersionEntity();
        v.id = UUID.randomUUID();
        v.mediaAssetId = mediaAssetId;
        v.version = version;
        v.contentHash = contentHash;
        v.filename = filename;
        v.mimeType = mimeType;
        v.fileSize = fileSize;
        v.storageKey = storageKey;
        v.storedAt = Instant.now();
        return v;
    }

    public UUID getId() { return id; }
    public UUID getMediaAssetId() { return mediaAssetId; }
    public int getVersion() { return version; }
    public String getContentHash() { return contentHash; }
    public String getFilename() { return filename; }
    public String getMimeType() { return mimeType; }
    public long getFileSize() { return fileSize; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getArtifactsJson() { return artifactsJson; }
    public void setArtifactsJson(String artifactsJson) { this.artifactsJson = artifactsJson; }
    public Instant getStoredAt() { return storedAt; }
}
