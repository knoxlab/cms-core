package com.powercms.cms.identity.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class UserControllerSecurityTests {
    @Test
    void listRequiresSystemAdminRole() throws Exception {
        Method list = UserController.class.getDeclaredMethod("list", java.util.UUID.class);
        PreAuthorize authorization = list.getAnnotation(PreAuthorize.class);
        assertThat(authorization).isNotNull();
        assertThat(authorization.value())
            .isEqualTo("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)");
    }

    @Test
    void getAllowsSelfOrSystemAdmin() throws Exception {
        Method get = UserController.class.getDeclaredMethod("get", java.util.UUID.class);
        assertThat(get.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("(hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication))"
                + " or @identityAuthorization.isSelf(authentication, #userId)");
    }
}
