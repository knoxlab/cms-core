package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.dto.ApiTokenCreatedResponse;
import com.powercms.cms.identity.dto.ApiTokenResponse;
import com.powercms.cms.identity.dto.CreateApiTokenRequest;
import com.powercms.cms.identity.entity.ApiTokenEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.ApiTokenRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ApiTokenService {

    private final ApiTokenRepository tokens;
    private final UserRepository users;
    private final SiteRepository sites;
    private final SiteCapabilityEvaluator capabilities;
    private final UserDetailsService userDetails;
    private final IdentityProperties properties;
    private final AuditService audit;

    public ApiTokenService(ApiTokenRepository tokens, UserRepository users, SiteRepository sites,
                           SiteCapabilityEvaluator capabilities, UserDetailsService userDetails,
                           IdentityProperties properties, AuditService audit) {
        this.tokens = tokens;
        this.users = users;
        this.sites = sites;
        this.capabilities = capabilities;
        this.userDetails = userDetails;
        this.properties = properties;
        this.audit = audit;
    }

    /** 社区版 API Token 数量上限（矩阵 F2：≤5 个）。 */
    static final int MAX_API_TOKENS = 5;

    public ApiTokenCreatedResponse create(UUID operatorId, CreateApiTokenRequest req) {
        if (req.allowedFrom() != null && !req.allowedFrom().isBlank()) {
            // 矩阵 F2：社区版不提供来源 IP/CIDR 限制
            throw new BusinessException(ErrorCodes.FORBIDDEN, "社区版不支持 Token 来源限制");
        }
        if (tokens.count() >= MAX_API_TOKENS) {
            throw new BusinessException(ErrorCodes.FORBIDDEN,
                "社区版最多签发 " + MAX_API_TOKENS + " 个 API Token");
        }
        requireUser(operatorId);
        UUID siteId = req.siteId();
        if (siteId != null) {
            if (!sites.existsById(siteId)) {
                throw new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在");
            }
            if (!capabilities.isSystemAdmin(operatorId)
                && !capabilities.has(operatorId, siteId, SiteCapabilities.ADMIN)) {
                throw new BusinessException(ErrorCodes.FORBIDDEN, "只能为有管理权限的站点签发 Token");
            }
        }
        Instant expiresAt = req.expiresAt();
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "过期时间必须晚于当前时间");
        }
        int maxDays = Math.max(1, properties.getMaxTokenTtlDays());
        if (expiresAt.isAfter(now.plus(maxDays, ChronoUnit.DAYS))) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "Token 有效期最长 " + maxDays + " 天");
        }
        List<String> scopes = normalizeScopes(req.scopes());
        TokenSecrets.Issued issued = TokenSecrets.issue();
        ApiTokenEntity entity = new ApiTokenEntity();
        entity.setPublicId(issued.publicId());
        entity.setUserId(operatorId);
        entity.setSiteId(siteId);
        entity.setName(req.name().trim());
        entity.setTokenHash(issued.sha256Hex());
        entity.setScopes(scopesToJson(scopes));
        entity.setExpiresAt(expiresAt);
        entity.setAllowedFrom(blankToNull(req.allowedFrom()));
        entity.setCreatedBy(operatorId);
        entity = tokens.save(entity);
        audit.write(actor(operatorId), "TOKEN_CREATE", "ApiToken", entity.getId().toString(),
            siteId, "SUCCESS", entity.getPublicId());
        return new ApiTokenCreatedResponse(entity.getId(), entity.getPublicId(), entity.getName(),
            entity.getSiteId(), scopes, entity.getExpiresAt(), entity.getAllowedFrom(),
            entity.getCreatedAt(), issued.plaintext());
    }

    @Transactional(readOnly = true)
    public List<ApiTokenResponse> listMine(UUID operatorId) {
        return tokens.findByUserIdOrderByCreatedAtDesc(operatorId).stream()
            .map(ApiTokenService::toResponse)
            .toList();
    }

    /** 管理端全量 Token 列表（FR-AGT-MCP-004 / FR-IAM-005）：仅 system-admin，含属主用户名（脱敏审计视角）。 */
    @Transactional(readOnly = true)
    public List<AdminApiTokenView> listAllForAdmin() {
        return tokens.findAllByOrderByCreatedAtDesc().stream()
            .map(token -> new AdminApiTokenView(
                toResponse(token),
                users.findById(token.getUserId()).map(UserEntity::getUsername).orElse(token.getUserId().toString()),
                users.findById(token.getCreatedBy()).map(UserEntity::getUsername).orElse(null)))
            .toList();
    }

    public record AdminApiTokenView(ApiTokenResponse token, String ownerUsername, String createdByName) {}

    public void delete(UUID tokenId, UUID operatorId) {
        ApiTokenEntity token = tokens.findById(tokenId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "Token 不存在"));
        if (!token.getUserId().equals(operatorId) && !capabilities.isSystemAdmin(operatorId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "只能删除自己的 Token");
        }
        tokens.delete(token);
        audit.write(actor(operatorId), "TOKEN_DELETE", "ApiToken", tokenId.toString(),
            token.getSiteId(), "SUCCESS", token.getPublicId());
    }

    public void revoke(UUID tokenId, UUID operatorId) {
        if (!capabilities.isSystemAdmin(operatorId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "仅系统管理员可吊销 Token");
        }
        ApiTokenEntity token = tokens.findById(tokenId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "Token 不存在"));
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
            tokens.save(token);
        }
        audit.write(actor(operatorId), "TOKEN_REVOKE", "ApiToken", tokenId.toString(),
            token.getSiteId(), "SUCCESS", token.getPublicId());
    }

    /**
     * TC-M04-004：判定该凭证是否**仅因过期**而失效——401 文案据此区分「过期」与「非法」。
     *
     * <p>只对**持有效密钥**的令牌返回 true（避免拿过期令牌的 publicId 探测他人令牌状态）；
     * 已撤销、IP 不在白名单、用户停用等一律不算「过期」，仍按非法凭证处理。</p>
     */
    @Transactional(readOnly = true)
    public boolean isExpired(String plaintext) {
        Optional<String> publicId = TokenSecrets.publicIdOf(plaintext);
        if (publicId.isEmpty()) return false;
        ApiTokenEntity token = tokens.findByPublicId(publicId.get()).orElse(null);
        if (token == null || token.getRevokedAt() != null) return false;
        if (!TokenSecrets.matches(plaintext, token.getTokenHash())) return false;
        Instant expiresAt = token.getExpiresAt();
        return expiresAt != null && !expiresAt.isAfter(Instant.now());
    }

    @Transactional(readOnly = true)
    public Optional<AuthenticatedUserPrincipal> authenticate(String plaintext, String clientIp) {
        Optional<String> publicId = TokenSecrets.publicIdOf(plaintext);
        if (publicId.isEmpty()) return Optional.empty();
        ApiTokenEntity token = tokens.findByPublicId(publicId.get()).orElse(null);
        if (token == null || !TokenSecrets.matches(plaintext, token.getTokenHash())) return Optional.empty();
        Instant now = Instant.now();
        if (token.getRevokedAt() != null || !token.getExpiresAt().isAfter(now)) return Optional.empty();
        if (!CidrMatcher.isAllowed(token.getAllowedFrom(), clientIp)) return Optional.empty();
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

    static List<String> parseScopes(String json) {
        if (json == null || json.isBlank()) return List.of();
        LinkedHashSet<String> scopes = new LinkedHashSet<>(SiteCapabilities.parse(json));
        String lower = json.toLowerCase(Locale.ROOT);
        for (String mcpScope : McpScopes.ALL) {
            if (lower.contains("\"" + mcpScope + "\"") || lower.contains("'" + mcpScope + "'")) {
                scopes.add(mcpScope);
            }
        }
        if (lower.contains("\"*\"") || lower.contains("'*'")) {
            scopes.add("*");
        }
        return List.copyOf(scopes);
    }

    static String scopesToJson(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < scopes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(scopes.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private static List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) continue;
            String value = scope.trim().toLowerCase(Locale.ROOT);
            if ("*".equals(value)) {
                normalized.add("*");
                continue;
            }
            if (McpScopes.isMcpScope(value)) {
                normalized.add(value);
                continue;
            }
            String cap = SiteCapabilities.normalize(value);
            if (!SiteCapabilities.ALL.contains(cap)) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不支持的 Token scope: " + scope);
            }
            normalized.add(cap);
        }
        return List.copyOf(normalized);
    }

    private static ApiTokenResponse toResponse(ApiTokenEntity token) {
        return new ApiTokenResponse(token.getId(), token.getPublicId(), token.getName(), token.getSiteId(),
            parseScopes(token.getScopes()), token.getExpiresAt(), token.getAllowedFrom(),
            token.getRevokedAt(), token.getCreatedAt());
    }

    private UserEntity requireUser(UUID userId) {
        return users.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "用户不存在"));
    }

    private String actor(UUID operatorId) {
        return users.findById(operatorId).map(UserEntity::getUsername).orElse(operatorId.toString());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
