package com.powercms.cms.content.relation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRelationRepository extends JpaRepository<ContentRelationEntity, UUID> {
  List<ContentRelationEntity> findBySiteIdOrderByRelationNameAsc(UUID siteId);

  Optional<ContentRelationEntity> findBySiteIdAndRelationName(UUID siteId, String relationName);

  boolean existsBySiteIdAndRelationName(UUID siteId, String relationName);
}
