package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.OAuthAuthorizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OAuthAuthorizationRepository extends JpaRepository<OAuthAuthorizationEntity, UUID> {

    Optional<OAuthAuthorizationEntity> findByCodeHash(String codeHash);
}
