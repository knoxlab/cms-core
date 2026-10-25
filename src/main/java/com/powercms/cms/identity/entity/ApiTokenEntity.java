package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_token")
public class ApiTokenEntity {

    @Id
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 32)
    private String publicId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(length = 1000)
    private String scopes;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "allowed_from", length = 500)
    private String allowedFrom;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getAllowedFrom() { return allowedFrom; }
    public void setAllowedFrom(String allowedFrom) { this.allowedFrom = allowedFrom; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
