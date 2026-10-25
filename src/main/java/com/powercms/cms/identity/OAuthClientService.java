package com.powercms.cms.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.dto.CreateOAuthClientRequest;
import com.powercms.cms.identity.dto.OAuthClientCreatedResponse;
import com.powercms.cms.identity.entity.OAuthClientEntity;
import com.powercms.cms.identity.repo.OAuthClientRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * OAuth 客户端管理（FR-AGT-MCP-013 / FR-AGT-SEC-006）：注册、吊销、凭据校验。
 * client_secret 按机密管理（只存 SHA-256，创建时唯一一次返回明文），纳入系统管理员审计。
 */
@Service
@Transactional
public class OAuthClientService {

    private final OAuthClientRepository clients;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public OAuthClientService(OAuthClientRepository clients, AuditService audit, ObjectMapper objectMapper) {
        this.clients = clients;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public OAuthClientCreatedResponse register(UUID operatorId, CreateOAuthClientRequest req) {
        String secret = OAuthCrypto.randomToken("pcm_ocs");
        String clientId = OAuthCrypto.randomToken("pcm_ocid");
        List<String> scopes = normalizeScopes(req.scopes());

        OAuthClientEntity entity = new OAuthClientEntity();
        entity.setClientId(clientId);
        entity.setClientSecretHash(OAuthCrypto.sha256Hex(secret));
        entity.setName(req.name().trim());
        entity.setRedirectUris(toJson(req.redirectUris()));
        entity.setScopes(toJson(scopes));
        entity.setCreatedBy(operatorId);
        entity = clients.save(entity);

        audit.write(operatorId.toString(), "oauth.client.create", "OAuthClient", entity.getId().toString(),
            null, "SUCCESS", "client_id=" + clientId + " name=" + entity.getName());
        return new OAuthClientCreatedResponse(clientId, entity.getName(), req.redirectUris(),
            scopes, entity.getCreatedAt(), secret);
    }

    /** 管理端列表（FR-AGT-MCP-013）：仅 system-admin，不含任何机密。 */
    @Transactional(readOnly = true)
    public List<OAuthClientAdminView> listAll() {
        return clients.findAllByOrderByCreatedAtDesc().stream()
            .map(c -> new OAuthClientAdminView(c.getId(), c.getClientId(), c.getName(),
                parseList(c.getRedirectUris()), parseScopes(c.getScopes()),
                c.getRevokedAt(), c.getCreatedAt()))
            .toList();
    }

    public record OAuthClientAdminView(UUID id, String clientId, String name,
                                       List<String> redirectUris, List<String> scopes,
                                       Instant revokedAt, Instant createdAt) {}

    public void revoke(String clientId, UUID operatorId) {
        OAuthClientEntity client = clients.findByClientId(clientId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "OAuth 客户端不存在"));
        if (client.getRevokedAt() == null) {
            client.setRevokedAt(Instant.now());
            clients.save(client);
        }
        audit.write(operatorId.toString(), "oauth.client.revoke", "OAuthClient", client.getId().toString(),
            null, "SUCCESS", "client_id=" + clientId);
    }

    /** 客户端凭据认证（token 端点用）：client_secret 校验 + 未吊销。 */
    public OAuthClientEntity authenticate(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "缺少客户端凭据");
        }
        OAuthClientEntity client = clients.findByClientId(clientId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.UNAUTHENTICATED, "客户端不存在"));
        if (client.getRevokedAt() != null) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "客户端已吊销");
        }
        if (!OAuthCrypto.sha256Hex(clientSecret).equals(client.getClientSecretHash())) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "客户端凭据无效");
        }
        return client;
    }

    /** 授权端点按 client_id 识别客户端（不验证 secret），校验未吊销。 */
    public OAuthClientEntity authenticateById(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "缺少 client_id");
        }
        OAuthClientEntity client = clients.findByClientId(clientId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.BAD_REQUEST, "客户端不存在"));
        if (client.getRevokedAt() != null) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "客户端已吊销");
        }
        return client;
    }

    /** 校验客户端请求的 scope 是否在其授权范围内。 */
    public List<String> scopesAllowed(OAuthClientEntity client, List<String> requested) {
        List<String> registered = parseScopes(client.getScopes());
        List<String> scopes = normalizeScopes(requested);
        for (String scope : scopes) {
            if (!registered.contains(scope)) {
                throw new BusinessException(ErrorCodes.TOKEN_SCOPE_DENIED,
                    "请求的 scope 超出客户端授权范围：" + scope);
            }
        }
        return scopes.isEmpty() ? List.of(McpScopes.READ) : scopes;
    }

    List<String> parseScopes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseList(String json) {
        return parseScopes(json);
    }

    private static List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return List.of(McpScopes.READ);
        return scopes.stream()
            .map(s -> s == null ? "" : s.trim().toLowerCase(Locale.ROOT))
            .filter(McpScopes::isMcpScope)
            .distinct()
            .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "JSON 序列化失败");
        }
    }
}
