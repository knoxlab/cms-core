package com.powercms.cms.identity.permission.repo;

import com.powercms.cms.identity.permission.entity.ProgramAccessPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProgramAccessPolicyRepository extends JpaRepository<ProgramAccessPolicyEntity, UUID> {

    Optional<ProgramAccessPolicyEntity> findByObjectTypeAndObjectId(String objectType, UUID objectId);
}
