package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.OAuthClientEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OAuthClientRepository extends JpaRepository<OAuthClientEntity, UUID> {

    Optional<OAuthClientEntity> findByClientId(String clientId);

    Optional<OAuthClientEntity> findByClientSecretHash(String clientSecretHash);

    List<OAuthClientEntity> findAllByOrderByCreatedAtDesc();
}
