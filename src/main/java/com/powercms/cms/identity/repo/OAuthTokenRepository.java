package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.OAuthTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OAuthTokenRepository extends JpaRepository<OAuthTokenEntity, UUID> {

    Optional<OAuthTokenEntity> findByTokenHash(String tokenHash);
}
