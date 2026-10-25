package com.powercms.cms.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.entity.OAuthClientEntity;
import com.powercms.cms.identity.entity.OAuthTokenEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.OAuthAuthorizationRepository;
import com.powercms.cms.identity.repo.OAuthTokenRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-AGT-MCP-013：OAuth 授权码/access/refresh 令牌签发与校验，与 API Token 共用校验面。 */
@ExtendWith(MockitoExtension.class)
class OAuthTokenServiceTest {

    @Mock OAuthAuthorizationRepository authorizations;
    @Mock OAuthTokenRepository tokens;
    @Mock UserRepository users;
    @Mock UserDetailsService userDetails;
    @Mock AuditService audit;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OAuthTokenService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private OAuthClientEntity client;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new OAuthTokenService(authorizations, tokens, users, userDetails, audit, objectMapper,
            3600, 86400, 300);
        client = new OAuthClientEntity();
        client.setId(clientId);
        client.setClientId("pcm_ocid_test");
        client.setName("test-client");

        user = new UserEntity();
        user.setId(userId);
        user.setUsername("agent-user");
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setPasswordHash("{bcrypt}x");

        var details = User.withUsername("agent-user").password("x").roles("editor").build();
        lenient().when(userDetails.loadUserByUsername("agent-user"))
            .thenReturn(new AuthenticatedUserPrincipal(userId, details));
        lenient().when(users.findById(userId)).thenReturn(Optional.of(user));
        // 模拟 @PrePersist 设置 id（JPA 环境自动触发）
        lenient().when(tokens.save(any())).thenAnswer(inv -> {
            OAuthTokenEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    private OAuthClientEntity stubGrant(String code, List<String> scopes, String codeChallenge,
                                        String codeChallengeMethod) {
        OAuthClientEntity unused = client;
        var grant = new com.powercms.cms.identity.entity.OAuthAuthorizationEntity();
        grant.setId(UUID.randomUUID());
        grant.setCodeHash(OAuthCrypto.sha256Hex(code));
        grant.setClientId(clientId);
        grant.setUserId(userId);
        grant.setRedirectUri("http://client/cb");
        grant.setScopes(toJson(scopes));
        grant.setResource(OAuthTokenService.MCP_RESOURCE);
        grant.setCodeChallenge(codeChallenge);
        grant.setCodeChallengeMethod(codeChallengeMethod);
        grant.setExpiresAt(Instant.now().plusSeconds(300));
        when(authorizations.findByCodeHash(OAuthCrypto.sha256Hex(code))).thenReturn(Optional.of(grant));
        return client;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void exchangeAuthorizationCodeIssuesAccessAndRefreshTokens() {
        String code = "pcm_oac_testcode";
        stubGrant(code, List.of(McpScopes.READ, McpScopes.DRAFT), null, null);

        OAuthTokenService.TokenResponse response = service.exchangeAuthorizationCode(
            client, code, "http://client/cb", null, null);

        assertNotNull(response.accessToken());
        assertNotNull(response.refreshToken());
        assertTrue(response.accessToken().startsWith("pcm_oat_"));
        assertTrue(response.refreshToken().startsWith("pcm_ort_"));
        assertEquals("DPoP", response.tokenType());
        assertTrue(response.scope().contains("mcp:read"));
        assertTrue(response.scope().contains("mcp:draft"));

        // 两个 token 都落库
        ArgumentCaptor<OAuthTokenEntity> captor = ArgumentCaptor.forClass(OAuthTokenEntity.class);
        verify(tokens, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
            .anyMatch(t -> OAuthTokenEntity.KIND_ACCESS.equals(t.getKind())));
        assertTrue(captor.getAllValues().stream()
            .anyMatch(t -> OAuthTokenEntity.KIND_REFRESH.equals(t.getKind())));
    }

    @Test
    void authorizationCodeIsOneShot() {
        String code = "pcm_oac_once";
        stubGrant(code, List.of(McpScopes.READ), null, null);

        service.exchangeAuthorizationCode(client, code, "http://client/cb", null, null);
        // 二次兑换：授权码已消费
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.exchangeAuthorizationCode(client, code, "http://client/cb", null, null));
        assertEquals("BAD_REQUEST", ex.getCode());
    }

    @Test
    void pkceVerificationRejectsWrongVerifier() {
        String code = "pcm_oac_pkce";
        String challenge = OAuthCrypto.pkceChallenge("correct-verifier");
        stubGrant(code, List.of(McpScopes.READ), challenge, "S256");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.exchangeAuthorizationCode(client, code, "http://client/cb", "wrong-verifier", null));
        assertEquals("BAD_REQUEST", ex.getCode());
        assertTrue(ex.getMessage().contains("PKCE"));
    }

    @Test
    void redirectUriMismatchRejected() {
        String code = "pcm_oac_redirect";
        stubGrant(code, List.of(McpScopes.READ), null, null);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.exchangeAuthorizationCode(client, code, "http://evil/cb", null, null));
        assertEquals("BAD_REQUEST", ex.getCode());
    }

    @Test
    void authenticateCarriesMcpScopes() {
        String accessToken = "pcm_oat_abc";
        OAuthTokenEntity token = new OAuthTokenEntity();
        token.setId(UUID.randomUUID());
        token.setTokenHash(OAuthCrypto.sha256Hex(accessToken));
        token.setKind(OAuthTokenEntity.KIND_ACCESS);
        token.setClientId(clientId);
        token.setUserId(userId);
        token.setScopes("[\"mcp:read\",\"mcp:draft\"]");
        token.setResource(OAuthTokenService.MCP_RESOURCE);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        when(tokens.findByTokenHash(OAuthCrypto.sha256Hex(accessToken))).thenReturn(Optional.of(token));

        Optional<AuthenticatedUserPrincipal> principal = service.authenticate(accessToken, null);
        assertTrue(principal.isPresent());
        assertTrue(principal.get().isApiToken());
        assertTrue(principal.get().getTokenScopes().contains("mcp:read"));
        assertTrue(principal.get().getTokenScopes().contains("mcp:draft"));
        assertEquals(McpScopes.L1, McpScopes.levelOf(principal.get().getTokenScopes()));
    }

    @Test
    void authenticateRejectsExpiredToken() {
        String accessToken = "pcm_oat_expired";
        OAuthTokenEntity token = new OAuthTokenEntity();
        token.setId(UUID.randomUUID());
        token.setTokenHash(OAuthCrypto.sha256Hex(accessToken));
        token.setKind(OAuthTokenEntity.KIND_ACCESS);
        token.setClientId(clientId);
        token.setUserId(userId);
        token.setScopes("[\"mcp:read\"]");
        token.setExpiresAt(Instant.now().minusSeconds(5));
        when(tokens.findByTokenHash(OAuthCrypto.sha256Hex(accessToken))).thenReturn(Optional.of(token));

        assertFalse(service.authenticate(accessToken, null).isPresent());
    }

    @Test
    void authenticateRejectsDpopJktMismatch() {
        String accessToken = "pcm_oat_dpop";
        OAuthTokenEntity token = new OAuthTokenEntity();
        token.setId(UUID.randomUUID());
        token.setTokenHash(OAuthCrypto.sha256Hex(accessToken));
        token.setKind(OAuthTokenEntity.KIND_ACCESS);
        token.setClientId(clientId);
        token.setUserId(userId);
        token.setScopes("[\"mcp:read\"]");
        token.setCnfJkt("bound-jkt");
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        when(tokens.findByTokenHash(OAuthCrypto.sha256Hex(accessToken))).thenReturn(Optional.of(token));

        // jkt 不匹配 → 拒绝
        assertFalse(service.authenticate(accessToken, "other-jkt").isPresent());
        // jkt 匹配 → 通过
        assertTrue(service.authenticate(accessToken, "bound-jkt").isPresent());
    }

    @Test
    void refreshRotatesOldRefreshToken() {
        String refreshToken = "pcm_ort_old";
        OAuthTokenEntity existing = new OAuthTokenEntity();
        existing.setId(UUID.randomUUID());
        existing.setTokenHash(OAuthCrypto.sha256Hex(refreshToken));
        existing.setKind(OAuthTokenEntity.KIND_REFRESH);
        existing.setClientId(clientId);
        existing.setUserId(userId);
        existing.setScopes("[\"mcp:read\"]");
        existing.setResource(OAuthTokenService.MCP_RESOURCE);
        existing.setExpiresAt(Instant.now().plusSeconds(86400));
        when(tokens.findByTokenHash(OAuthCrypto.sha256Hex(refreshToken))).thenReturn(Optional.of(existing));

        OAuthTokenService.TokenResponse response = service.refresh(client, refreshToken, null);
        assertNotNull(response.accessToken());

        // 旧 refresh 被吊销
        assertNotNull(existing.getRevokedAt());
    }
}
