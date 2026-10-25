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
@Table(name = "release_media_version")
@IdClass(ReleaseMediaVersionEntity.Key.class)
public class ReleaseMediaVersionEntity {
    @Id @Column(name = "release_id") private UUID releaseId;
    @Id @Column(name = "media_asset_id") private UUID mediaAssetId;
    @Column(name = "media_version", nullable = false) private int mediaVersion;
    @Column(name = "content_hash", nullable = false, length = 128) private String contentHash;
    @Column(nullable = false, length = 500) private String filename;
    @Column(name = "mime_type", length = 100) private String mimeType;
    @Column(name = "file_size", nullable = false) private long fileSize;
    @Column(name = "storage_key", nullable = false, length = 500) private String storageKey;
    @Column(name = "output_path", nullable = false, length = 1000) private String outputPath;

    public static class Key implements Serializable {
        public UUID releaseId;
        public UUID mediaAssetId;
        public Key() {}
        public Key(UUID releaseId, UUID mediaAssetId) {
            this.releaseId = releaseId;
            this.mediaAssetId = mediaAssetId;
        }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(releaseId, k.releaseId) && Objects.equals(mediaAssetId, k.mediaAssetId);
        }
        @Override public int hashCode() { return Objects.hash(releaseId, mediaAssetId); }
    }

    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID releaseId) { this.releaseId = releaseId; }
    public UUID getMediaAssetId() { return mediaAssetId; }
    public void setMediaAssetId(UUID mediaAssetId) { this.mediaAssetId = mediaAssetId; }
    public int getMediaVersion() { return mediaVersion; }
    public void setMediaVersion(int mediaVersion) { this.mediaVersion = mediaVersion; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
}
