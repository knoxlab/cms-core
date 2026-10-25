package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.SiteAuthorizationPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SiteAuthorizationPolicyRepository extends JpaRepository<SiteAuthorizationPolicyEntity, UUID> {
}
