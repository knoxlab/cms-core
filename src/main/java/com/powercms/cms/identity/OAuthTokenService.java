package com.powercms.cms.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.entity.OAuthAuthorizationEntity;
import com.powercms.cms.identity.entity.OAuthClientEntity;
import com.powercms.cms.identity.entity.OAuthTokenEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.OAuthAuthorizationRepository;
import com.powercms.cms.identity.repo.OAuthTokenRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * OAuth 令牌服务（FR-AGT-MCP-013）：授权码签发/兑换、access/refresh token 签发/校验/吊销。
 * access token 认证后构造 {@link AuthenticatedUserPrincipal}，与 API Token 共用同一权限校验面
 * （token 验签/有效期/吊销 → scope 求交 → 用户角色 → 对象级 ACL），越权行为一致。
 */
@Service
@Transactional
public class OAuthTokenService {

    static final String ACCESS_PREFIX = "pcm_oat";
    static final String REFRESH_PREFIX = "pcm_ort";
    static final String CODE_PREFIX = "pcm_oac";

    /** RFC 8707：资源指示器，MCP 接入固定指向 /api/mcp。 */
    public static final String MCP_RESOURCE = "/api/mcp";

    private final OAuthAuthorizationRepository authorizations;
    private final OAuthTokenRepository tokens;
    private final UserRepository users;
    private final UserDetailsService userDetails;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;
    private final long codeTtlSeconds;

    public OAuthTokenService(OAuthAuthorizationRepository authorizations, OAuthTokenRepository tokens,
                             UserRepository users, UserDetailsService userDetails, AuditService audit,
                             ObjectMapper objectMapper,
                             @Value("${powercms.oauth.access-token-ttl-seconds:3600}") long accessTtlSeconds,
                             @Value("${powercms.oauth.refresh-token-ttl-seconds:2592000}") long refreshTtlSeconds,
                             @Value("${powercms.oauth.code-ttl-seconds:300}") long codeTtlSeconds) {
        this.authorizations = authorizations;
        this.tokens = tokens;
        this.users = users;
        this.userDetails = userDetails;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.accessTtlSeconds = Math.max(1, accessTtlSeconds);
        this.refreshTtlSeconds = Math.max(1, refreshTtlSeconds);
        this.codeTtlSeconds = Math.max(1, codeTtlSeconds);
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                String refreshToken, String scope) {}

    /** 签发授权码（授权端点确认后），返回授权码明文（redirect 用）。 */
    public String issueAuthorizationCode(OAuthClientEntity client, UUID userId, String redirectUri,
                                         List<String> scopes, String resource, String codeChallenge,
                                         String codeChallengeMethod) {
        String code = OAuthCrypto.randomToken(CODE_PREFIX);
        OAuthAuthorizationEntity grant = new OAuthAuthorizationEntity();
        grant.setCodeHash(OAuthCrypto.sha256Hex(code));
        grant.setClientId(client.getId());
        grant.setUserId(userId);
        grant.setRedirectUri(redirectUri);
        grant.setScopes(toJson(scopes));
        grant.setResource(resource);
        grant.setCodeChallenge(blankToNull(codeChallenge));
        grant.setCodeChallengeMethod(blankToNull(codeChallengeMethod));
        grant.setExpiresAt(Instant.now().plusSeconds(codeTtlSeconds));
        authorizations.save(grant);
        audit.write(userId.toString(), "oauth.code.issue", "OAuthAuthorization", grant.getId().toString(),
            null, "SUCCESS", "client=" + client.getClientId() + " resource=" + resource);
        return code;
    }

    /** 授权码兑换 access + refresh token（token 端点，PKCE 校验 + DPoP jkt 绑定）。 */
    public TokenResponse exchangeAuthorizationCode(OAuthClientEntity client, String code, String redirectUri,
                                                   String codeVerifier, String dpopJkt) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "缺少授权码");
        }
        OAuthAuthorizationEntity grant = authorizations.findByCodeHash(OAuthCrypto.sha256Hex(code))
            .orElseThrow(() -> new BusinessException(ErrorCodes.BAD_REQUEST, "授权码无效"));
        if (grant.getConsumedAt() != null) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "授权码已使用");
        }
        if (Instant.now().isAfter(grant.getExpiresAt())) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "授权码已过期");
        }
        if (!grant.getClientId().equals(client.getId())) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "授权码与客户端不匹配");
        }
        if (!grant.getRedirectUri().equals(redirectUri)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "redirect_uri 与授权请求不一致");
        }
        verifyPkce(grant, codeVerifier);

        grant.setConsumedAt(Instant.now());
        authorizations.save(grant);

        List<String> scopes = parseScopes(grant.getScopes());
        TokenResponse response = issueTokens(client, grant.getUserId(), scopes, grant.getResource(), dpopJkt);
        audit.write(grant.getUserId().toString(), "oauth.code.exchange", "OAuthAuthorization",
            grant.getId().toString(), null, "SUCCESS",
            "client=" + client.getClientId() + " scopes=" + scopes);
        return response;
    }

    /** refresh token 换取新 token（token 端点）。 */
    public TokenResponse refresh(OAuthClientEntity client, String refreshToken, String dpopJkt) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "缺少 refresh_token");
        }
        OAuthTokenEntity existing = tokens.findByTokenHash(OAuthCrypto.sha256Hex(refreshToken))
            .orElseThrow(() -> new BusinessException(ErrorCodes.BAD_REQUEST, "refresh_token 无效"));
        if (!OAuthTokenEntity.KIND_REFRESH.equals(existing.getKind())) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "token 类型非 refresh");
        }
        if (existing.getRevokedAt() != null || Instant.now().isAfter(existing.getExpiresAt())) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "refresh_token 已失效");
        }
        if (!existing.getClientId().equals(client.getId())) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "refresh_token 与客户端不匹配");
        }
        // 吊销旧 refresh（旋转）
        existing.setRevokedAt(Instant.now());
        tokens.save(existing);

        List<String> scopes = parseScopes(existing.getScopes());
        return issueTokens(client, existing.getUserId(), scopes, existing.getResource(), dpopJkt);
    }

    /** 认证 access token（过滤器用），DPoP jkt 绑定时须匹配。 */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUserPrincipal> authenticate(String plaintext, String dpopJkt) {
        if (plaintext == null || !plaintext.startsWith(ACCESS_PREFIX)) return Optional.empty();
        OAuthTokenEntity token = tokens.findByTokenHash(OAuthCrypto.sha256Hex(plaintext)).orElse(null);
        if (token == null || !OAuthTokenEntity.KIND_ACCESS.equals(token.getKind())) return Optional.empty();
        Instant now = Instant.now();
        if (token.getRevokedAt() != null || !token.getExpiresAt().isAfter(now)) return Optional.empty();
        // DPoP 绑定：token 携带 cnf.jkt 时，请求必须提供匹配的 DPoP 证明 jkt
        if (token.getCnfJkt() != null && !token.getCnfJkt().equals(dpopJkt)) return Optional.empty();

        UserEntity user = users.findById(token.getUserId()).orElse(null);
        if (user == null || !user.isActive()) return Optional.empty();
        UserDetails details = userDetails.loadUserByUsername(user.getUsername());
        if (!details.isEnabled() || !details.isAccountNonLocked() || !details.isCredentialsNonExpired()) {
            return Optional.empty();
        }
        AuthenticatedUserPrincipal base = details instanceof AuthenticatedUserPrincipal principal
            ? principal
            : new AuthenticatedUserPrincipal(user.getId(), details);
        Set<String> scopes = new LinkedHashSet<>(parseScopes(token.getScopes()));
        return Optional.of(new AuthenticatedUserPrincipal(
            base.getUserId(), base, token.getId(), token.getSiteId(), Set.copyOf(scopes)));
    }

    /** 吊销 token（RFC 7009 / FR-AGT-SEC-006）。 */
    public void revoke(String tokenPlaintext) {
        if (tokenPlaintext == null || tokenPlaintext.isBlank()) return;
        tokens.findByTokenHash(OAuthCrypto.sha256Hex(tokenPlaintext)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
                tokens.save(token);
                audit.write("system", "oauth.token.revoke", "OAuthToken", token.getId().toString(),
                    null, "SUCCESS", "kind=" + token.getKind());
            }
        });
    }

    private TokenResponse issueTokens(OAuthClientEntity client, UUID userId, List<String> scopes,
                                      String resource, String dpopJkt) {
        String accessToken = OAuthCrypto.randomToken(ACCESS_PREFIX);
        String refreshToken = OAuthCrypto.randomToken(REFRESH_PREFIX);
        Instant now = Instant.now();

        OAuthTokenEntity access = new OAuthTokenEntity();
        access.setTokenHash(OAuthCrypto.sha256Hex(accessToken));
        access.setKind(OAuthTokenEntity.KIND_ACCESS);
        access.setClientId(client.getId());
        access.setUserId(userId);
        access.setScopes(toJson(scopes));
        access.setResource(resource);
        access.setCnfJkt(blankToNull(dpopJkt));
        access.setExpiresAt(now.plusSeconds(accessTtlSeconds));
        tokens.save(access);

        OAuthTokenEntity refresh = new OAuthTokenEntity();
        refresh.setTokenHash(OAuthCrypto.sha256Hex(refreshToken));
        refresh.setKind(OAuthTokenEntity.KIND_REFRESH);
        refresh.setClientId(client.getId());
        refresh.setUserId(userId);
        refresh.setScopes(toJson(scopes));
        refresh.setResource(resource);
        refresh.setCnfJkt(blankToNull(dpopJkt));
        refresh.setParentHash(access.getTokenHash());
        refresh.setExpiresAt(now.plusSeconds(refreshTtlSeconds));
        tokens.save(refresh);

        audit.write(userId.toString(), "oauth.token.issue", "OAuthToken", access.getId().toString(),
            null, "SUCCESS", "client=" + client.getClientId() + " scopes=" + scopes + " resource=" + resource);
        return new TokenResponse(accessToken, "DPoP", accessTtlSeconds, refreshToken, String.join(" ", scopes));
    }

    private void verifyPkce(OAuthAuthorizationEntity grant, String codeVerifier) {
        String challenge = grant.getCodeChallenge();
        if (challenge == null || challenge.isBlank()) {
            return; // 无 PKCE 绑定的授权码（非 MCP 标准场景，向后兼容）
        }
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "缺少 code_verifier");
        }
        String expected = "S256".equalsIgnoreCase(grant.getCodeChallengeMethod())
            ? OAuthCrypto.pkceChallenge(codeVerifier)
            : codeVerifier;
        if (!challenge.equals(expected)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "PKCE 校验失败");
        }
    }

    private List<String> parseScopes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "scope 序列化失败");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value;
    }
}
