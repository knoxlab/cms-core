package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    boolean existsByUsername(String username);

    long countByOrganizationIdAndStatus(UUID organizationId, String status);

    /** License 用量水位（adminUsers / frontendMembers）。 */
    long countByKindAndStatus(String kind, String status);

    java.util.List<UserEntity> findByKindAndHomeSiteId(String kind, UUID homeSiteId);
}
