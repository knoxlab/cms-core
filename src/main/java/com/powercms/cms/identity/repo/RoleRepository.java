package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    List<RoleEntity> findByOrganizationId(UUID organizationId);

    Optional<RoleEntity> findByOrganizationIdAndCode(UUID organizationId, String code);
}
