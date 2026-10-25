package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.workflow.RecipientEmailResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class IdentityRecipientEmailResolver implements RecipientEmailResolver {

    private final UserRepository users;

    IdentityRecipientEmailResolver(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<String> emailFor(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return users.findByUsername(username)
            .map(UserEntity::getEmail)
            .filter(email -> email != null && !email.isBlank());
    }
}
