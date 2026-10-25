package com.powercms.cms.model.repo;

import com.powercms.cms.model.entity.FieldDefinitionEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinitionEntity, UUID> {
  List<FieldDefinitionEntity> findByContentTypeIdOrderBySortOrder(UUID contentTypeId);

  /** 批量取字段定义（字段级索引 schema 按内容类型遍历；AC-CM-009）。 */
  List<FieldDefinitionEntity> findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(
      Collection<UUID> contentTypeIds);
}
