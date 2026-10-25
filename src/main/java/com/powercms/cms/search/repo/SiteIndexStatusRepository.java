package com.powercms.cms.search.repo;

import com.powercms.cms.search.entity.SiteIndexStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SiteIndexStatusRepository extends JpaRepository<SiteIndexStatusEntity, UUID> {
}
