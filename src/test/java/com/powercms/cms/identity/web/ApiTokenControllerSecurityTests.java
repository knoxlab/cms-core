package com.powercms.cms.identity.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenControllerSecurityTests {

    @Test
    void adminRevokeRequiresSystemAdmin() throws Exception {
        Method revoke = ApiTokenController.class.getDeclaredMethod("revoke", UUID.class, Principal.class);
        assertThat(revoke.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)");
    }

    @Test
    void selfTokenApisRequireAuthentication() throws Exception {
        Method create = ApiTokenController.class.getDeclaredMethod(
            "create", com.powercms.cms.identity.dto.CreateApiTokenRequest.class, Principal.class);
        assertThat(create.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)");
    }
}
