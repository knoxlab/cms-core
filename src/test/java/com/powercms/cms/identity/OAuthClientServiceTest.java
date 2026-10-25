package com.powercms.cms.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.dto.CreateOAuthClientRequest;
import com.powercms.cms.identity.dto.OAuthClientCreatedResponse;
import com.powercms.cms.identity.entity.OAuthClientEntity;
import com.powercms.cms.identity.repo.OAuthClientRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-AGT-MCP-013 / FR-AGT-SEC-006：OAuth 客户端注册/吊销/凭据校验/scope 求交。 */
@ExtendWith(MockitoExtension.class)
class OAuthClientServiceTest {

    @Mock OAuthClientRepository clients;
    @Mock AuditService audit;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OAuthClientService service;
    private final UUID operatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OAuthClientService(clients, audit, objectMapper);
    }

    @Test
    void registerHashesSecretAndReturnsPlaintextOnce() {
        when(clients.save(any())).thenAnswer(inv -> {
            OAuthClientEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });

        OAuthClientCreatedResponse created = service.register(operatorId,
            new CreateOAuthClientRequest("mcp-test", List.of("http://client/cb"),
                List.of(McpScopes.READ, McpScopes.DRAFT)));

        assertNotNull(created.clientSecret());
        assertTrue(created.clientSecret().startsWith("pcm_ocs_"));
        assertEquals("mcp-test", created.name());
        assertEquals(List.of(McpScopes.READ, McpScopes.DRAFT), created.scopes());

        ArgumentCaptor<OAuthClientEntity> captor = ArgumentCaptor.forClass(OAuthClientEntity.class);
        verify(clients).save(captor.capture());
        OAuthClientEntity saved = captor.getValue();
        // secret 只存哈希
        assertTrue(saved.getClientSecretHash().length() == 64);
        assertTrue(!saved.getClientSecretHash().equals(created.clientSecret()));
        assertEquals(OAuthCrypto.sha256Hex(created.clientSecret()), saved.getClientSecretHash());
    }

    @Test
    void authenticateValidatesCredentials() {
        String secret = "pcm_ocs_secret";
        OAuthClientEntity client = new OAuthClientEntity();
        client.setId(UUID.randomUUID());
        client.setClientId("pcm_ocid_x");
        client.setClientSecretHash(OAuthCrypto.sha256Hex(secret));
        when(clients.findByClientId("pcm_ocid_x")).thenReturn(Optional.of(client));

        // 正确凭据
        assertEquals(client, service.authenticate("pcm_ocid_x", secret));
        // 错误凭据
        assertThrows(BusinessException.class, () -> service.authenticate("pcm_ocid_x", "wrong"));
    }

    @Test
    void revokedClientCannotAuthenticate() {
        OAuthClientEntity client = new OAuthClientEntity();
        client.setId(UUID.randomUUID());
        client.setClientId("pcm_ocid_revoked");
        client.setClientSecretHash(OAuthCrypto.sha256Hex("pcm_ocs_x"));
        client.setRevokedAt(java.time.Instant.now());
        when(clients.findByClientId("pcm_ocid_revoked")).thenReturn(Optional.of(client));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.authenticate("pcm_ocid_revoked", "pcm_ocs_x"));
        assertEquals("UNAUTHENTICATED", ex.getCode());
    }

    @Test
    void scopesAllowedRejectsEscalation() {
        OAuthClientEntity client = new OAuthClientEntity();
        client.setId(UUID.randomUUID());
        client.setClientId("pcm_ocid_scope");
        client.setScopes("[\"mcp:read\"]");

        // 请求超出注册范围 → 拒绝
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.scopesAllowed(client, List.of(McpScopes.READ, McpScopes.PUBLISH)));
        assertEquals("TOKEN_SCOPE_DENIED", ex.getCode());

        // 范围内 → 通过
        assertEquals(List.of(McpScopes.READ), service.scopesAllowed(client, List.of(McpScopes.READ)));
    }

    @Test
    void listAllReturnsViewsWithoutSecrets() {
        OAuthClientEntity client = new OAuthClientEntity();
        client.setId(UUID.randomUUID());
        client.setClientId("pcm_ocid_list");
        client.setClientSecretHash("deadbeef");
        client.setName("MCP App");
        client.setRedirectUris("[\"http://client/cb\"]");
        client.setScopes("[\"mcp:read\",\"mcp:draft\"]");
        when(clients.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(client));

        List<OAuthClientService.OAuthClientAdminView> views = service.listAll();
        assertEquals(1, views.size());
        OAuthClientService.OAuthClientAdminView view = views.get(0);
        assertEquals("pcm_ocid_list", view.clientId());
        assertEquals("MCP App", view.name());
        assertEquals(List.of("http://client/cb"), view.redirectUris());
        assertEquals(List.of(McpScopes.READ, McpScopes.DRAFT), view.scopes());
    }
}
