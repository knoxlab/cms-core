package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * OAuth 授权码记录（短期、一次性）：绑定 client/redirect_uri/user/scopes/resource + PKCE code_challenge。
 */
@Entity
@Table(name = "oauth_authorization")
public class OAuthAuthorizationEntity {

    @Id
    private UUID id;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "redirect_uri", nullable = false, length = 2000)
    private String redirectUri;

    @Column(length = 1000)
    private String scopes;

    @Column(length = 500)
    private String resource;

    @Column(name = "code_challenge", length = 128)
    private String codeChallenge;

    @Column(name = "code_challenge_method", length = 10)
    private String codeChallengeMethod;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getCodeChallenge() { return codeChallenge; }
    public void setCodeChallenge(String codeChallenge) { this.codeChallenge = codeChallenge; }
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
    public void setCodeChallengeMethod(String codeChallengeMethod) { this.codeChallengeMethod = codeChallengeMethod; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
