package com.powercms.cms.content.repo;

import com.powercms.cms.content.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {
    List<CategoryEntity> findBySiteIdAndParentIdOrderBySortOrder(UUID siteId, UUID parentId);
    List<CategoryEntity> findBySiteId(UUID siteId);
}
