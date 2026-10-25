package com.powercms.cms.identity;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("identityAuthorization")
public class IdentityAuthorization {

    public boolean isSelf(Authentication authentication, UUID userId) {
        if (authentication == null || userId == null) return false;
        return authentication.getPrincipal() instanceof AuthenticatedUserPrincipal user
            && userId.equals(user.getUserId());
    }
}
