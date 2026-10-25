package com.powercms.cms.search.repo;

import com.powercms.cms.search.entity.SearchIndexMappingStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SearchIndexMappingStateRepository
    extends JpaRepository<SearchIndexMappingStateEntity, UUID> {

    Optional<SearchIndexMappingStateEntity> findBySiteIdAndTarget(UUID siteId, String target);

    List<SearchIndexMappingStateEntity> findBySiteIdOrderByTargetAsc(UUID siteId);
}
