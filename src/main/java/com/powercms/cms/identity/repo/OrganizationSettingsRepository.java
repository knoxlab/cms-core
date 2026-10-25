package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.OrganizationSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrganizationSettingsRepository extends JpaRepository<OrganizationSettingsEntity, UUID> {
}
