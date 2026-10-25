package com.powercms.cms.platform.security;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭据管理面会话门槛单测（规格 §10.2）：机读凭据不得自我扩权。
 */
class InteractiveSessionGuardTest {

    private final InteractiveSessionGuard guard = new InteractiveSessionGuard();

    private static AuthenticatedUserPrincipal sessionPrincipal() {
        return new AuthenticatedUserPrincipal(UUID.randomUUID(),
            new User("tester", "", List.of()));
    }

    private static AuthenticatedUserPrincipal machinePrincipal() {
        return new AuthenticatedUserPrincipal(UUID.randomUUID(),
            new User("tester", "", List.of()),
            UUID.randomUUID(),          // tokenId：API Token 与 OAuth access token 均非空
            null,
            Set.of("mcp:read"));
    }

    private static Authentication auth(AuthenticatedUserPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void interactiveSessionIsAllowed() {
        assertThat(guard.requireInteractive(auth(sessionPrincipal()))).isTrue();
    }

    @Test
    void anonymousRequestIsLeftToOtherConstraints() {
        assertThatCode(() -> guard.requireInteractive(null)).doesNotThrowAnyException();
    }

    @Test
    void apiTokenIsRejected() {
        assertThatThrownBy(() -> guard.requireInteractive(auth(machinePrincipal())))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.TOKEN_SCOPE_DENIED);
    }

    /** OAuth access token 与 API Token 走同一 principal 形态（tokenId 非空），须一并拒绝。 */
    @Test
    void oauthAccessTokenIsAlsoRejected() {
        AuthenticatedUserPrincipal oauthToken = new AuthenticatedUserPrincipal(UUID.randomUUID(),
            new User("tester", "", List.of()), UUID.randomUUID(), null, Set.of("mcp:read", "mcp:publish"));
        assertThatThrownBy(() -> guard.requireInteractive(auth(oauthToken)))
            .isInstanceOf(BusinessException.class);
    }
}
