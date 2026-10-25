package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.BackendLayoutEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BackendLayoutRepository extends JpaRepository<BackendLayoutEntity, UUID> {

    Optional<BackendLayoutEntity> findByCode(String code);
}
