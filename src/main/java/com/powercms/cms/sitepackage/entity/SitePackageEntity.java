package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_package", uniqueConstraints = @UniqueConstraint(columnNames = {"package_id", "version"}))
public class SitePackageEntity {

    @Id private UUID id;
    @Column(name = "package_id", nullable = false, length = 200) private String packageId;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 50) private String version;
    @Column(name = "schema_version", nullable = false, length = 20) private String schemaVersion = "1.0";
    @Column(columnDefinition = "text") private String vendor;
    @Column(name = "license_spdx", length = 100) private String licenseSpdx;
    @Column(name = "cms_version_range", length = 200) private String cmsVersionRange;
    @Column(name = "template_runtime", columnDefinition = "text") private String templateRuntime;
    @Column(columnDefinition = "text") private String entrypoints;
    @Column(name = "required_capabilities", columnDefinition = "text") private String requiredCapabilities;
    @Column(name = "manifest_checksums", columnDefinition = "text") private String manifestChecksums;
    @Column(columnDefinition = "text") private String signature;
    @Column(name = "package_digest", length = 128) private String packageDigest;
    @Column(name = "file_size", nullable = false) private long fileSize;
    @Column(name = "file_count", nullable = false) private int fileCount;
    @Column(name = "storage_key", length = 500) private String storageKey;
    @Column(name = "imported_site_id") private UUID importedSiteId;
    @Column(name = "imported_at") private Instant importedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public String getPackageId() { return packageId; } public void setPackageId(String v) { this.packageId = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public String getVersion() { return version; } public void setVersion(String v) { this.version = v; }
    public String getSchemaVersion() { return schemaVersion; } public void setSchemaVersion(String v) { this.schemaVersion = v; }
    public String getVendor() { return vendor; } public void setVendor(String v) { this.vendor = v; }
    public String getLicenseSpdx() { return licenseSpdx; } public void setLicenseSpdx(String v) { this.licenseSpdx = v; }
    public String getCmsVersionRange() { return cmsVersionRange; } public void setCmsVersionRange(String v) { this.cmsVersionRange = v; }
    public String getTemplateRuntime() { return templateRuntime; } public void setTemplateRuntime(String v) { this.templateRuntime = v; }
    public String getEntrypoints() { return entrypoints; } public void setEntrypoints(String v) { this.entrypoints = v; }
    public String getRequiredCapabilities() { return requiredCapabilities; } public void setRequiredCapabilities(String v) { this.requiredCapabilities = v; }
    public String getManifestChecksums() { return manifestChecksums; } public void setManifestChecksums(String v) { this.manifestChecksums = v; }
    public String getSignature() { return signature; } public void setSignature(String v) { this.signature = v; }
    public String getPackageDigest() { return packageDigest; } public void setPackageDigest(String v) { this.packageDigest = v; }
    public long getFileSize() { return fileSize; } public void setFileSize(long v) { this.fileSize = v; }
    public int getFileCount() { return fileCount; } public void setFileCount(int v) { this.fileCount = v; }
    public String getStorageKey() { return storageKey; } public void setStorageKey(String v) { this.storageKey = v; }
    public UUID getImportedSiteId() { return importedSiteId; } public void setImportedSiteId(UUID v) { this.importedSiteId = v; }
    public Instant getImportedAt() { return importedAt; } public void setImportedAt(Instant v) { this.importedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
