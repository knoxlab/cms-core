package com.powercms.cms.content.relation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRelationItemRepository
    extends JpaRepository<ContentRelationItemEntity, UUID> {
  List<ContentRelationItemEntity> findByRelationIdOrderBySortOrderAsc(UUID relationId);

  List<ContentRelationItemEntity> findByRelationIdAndSourceContentIdOrderBySortOrderAsc(
      UUID relationId, UUID sourceContentId);

  List<ContentRelationItemEntity> findByRelationIdAndTargetContentIdOrderBySortOrderAsc(
      UUID relationId, UUID targetContentId);

  Optional<ContentRelationItemEntity> findByRelationIdAndSourceContentIdAndTargetContentId(
      UUID relationId, UUID sourceContentId, UUID targetContentId);

  boolean existsByRelationId(UUID relationId);

  void deleteByRelationId(UUID relationId);

  List<ContentRelationItemEntity> findBySourceContentIdIn(java.util.Collection<UUID> sourceContentIds);

  List<ContentRelationItemEntity> findByTargetContentIdIn(java.util.Collection<UUID> targetContentIds);
}
