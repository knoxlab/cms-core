package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.entity.ApiTokenEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.ApiTokenRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiTokenServiceTest {

    private final ApiTokenRepository tokens = mock(ApiTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserDetailsService userDetails = mock(UserDetailsService.class);
    private ApiTokenService service;
    private TokenSecrets.Issued issued;
    private ApiTokenEntity token;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new ApiTokenService(tokens, users, mock(SiteRepository.class),
            mock(SiteCapabilityEvaluator.class), userDetails, new IdentityProperties(), mock(AuditService.class));
        issued = TokenSecrets.issue();
        UUID userId = UUID.randomUUID();
        user = new UserEntity();
        user.setId(userId);
        user.setUsername("bot");
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setPasswordHash("{bcrypt}x");
        token = new ApiTokenEntity();
        token.setId(UUID.randomUUID());
        token.setPublicId(issued.publicId());
        token.setUserId(userId);
        token.setTokenHash(issued.sha256Hex());
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        when(tokens.findByPublicId(issued.publicId())).thenReturn(Optional.of(token));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        var details = User.withUsername("bot").password("x").authorities("ROLE_system-admin").build();
        when(userDetails.loadUserByUsername("bot")).thenReturn(new AuthenticatedUserPrincipal(userId, details));
    }

    @Test
    void validTokenAuthenticates() {
        assertThat(service.authenticate(issued.plaintext(), "127.0.0.1")).isPresent();
    }

    @Test
    void expiredOrRevokedTokenIsRejected() {
        token.setExpiresAt(Instant.now().minusSeconds(5));
        assertThat(service.authenticate(issued.plaintext(), "127.0.0.1")).isEmpty();
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        token.setRevokedAt(Instant.now());
        assertThat(service.authenticate(issued.plaintext(), "127.0.0.1")).isEmpty();
    }

    @Test
    void cidrMismatchIsRejected() {
        token.setAllowedFrom("10.0.0.0/8");
        assertThat(service.authenticate(issued.plaintext(), "11.1.1.1")).isEmpty();
        assertThat(service.authenticate(issued.plaintext(), "10.9.9.9")).isPresent();
    }

    @Test
    void mcpScopesAreParsedAndCarried() {
        token.setScopes("[\"mcp:read\",\"mcp:draft\"]");
        AuthenticatedUserPrincipal principal = service.authenticate(issued.plaintext(), "127.0.0.1").orElseThrow();
        assertThat(principal.getTokenScopes()).contains("mcp:read", "mcp:draft");
        assertThat(McpScopes.levelOf(principal.getTokenScopes())).isEqualTo(McpScopes.L1);
    }

    @Test
    void adminListIncludesOwnerUsername() {
        UUID userId = UUID.randomUUID();
        ApiTokenEntity t = new ApiTokenEntity();
        t.setId(UUID.randomUUID());
        t.setPublicId("pcm_tok_pub");
        t.setUserId(userId);
        t.setCreatedBy(userId);
        t.setName("CI");
        t.setTokenHash("x");
        t.setScopes("[\"mcp:read\"]");
        t.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        when(tokens.findAllByOrderByCreatedAtDesc()).thenReturn(java.util.List.of(t));
        when(users.findById(userId)).thenReturn(Optional.of(user));

        var views = service.listAllForAdmin();
        assertThat(views).hasSize(1);
        assertThat(views.get(0).ownerUsername()).isEqualTo("bot");
        assertThat(views.get(0).token().scopes()).contains("mcp:read");
    }
}
