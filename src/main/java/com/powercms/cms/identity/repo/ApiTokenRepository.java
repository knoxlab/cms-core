package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.ApiTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiTokenRepository extends JpaRepository<ApiTokenEntity, UUID> {

    Optional<ApiTokenEntity> findByPublicId(String publicId);

    List<ApiTokenEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<ApiTokenEntity> findAllByOrderByCreatedAtDesc();
}
