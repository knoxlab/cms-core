package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * OAuth access token 与 refresh token（opaque，存哈希、可吊销；绑定 resource + DPoP cnf.jkt）。
 */
@Entity
@Table(name = "oauth_token")
public class OAuthTokenEntity {

    public static final String KIND_ACCESS = "ACCESS";
    public static final String KIND_REFRESH = "REFRESH";

    @Id
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(length = 1000)
    private String scopes;

    @Column(length = 500)
    private String resource;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "cnf_jkt", length = 64)
    private String cnfJkt;

    @Column(name = "parent_hash", length = 64)
    private String parentHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getCnfJkt() { return cnfJkt; }
    public void setCnfJkt(String cnfJkt) { this.cnfJkt = cnfJkt; }
    public String getParentHash() { return parentHash; }
    public void setParentHash(String parentHash) { this.parentHash = parentHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
