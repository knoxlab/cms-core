package com.powercms.cms.model.repo;

import com.powercms.cms.model.entity.FormLayoutEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormLayoutRepository extends JpaRepository<FormLayoutEntity, UUID> {
  List<FormLayoutEntity> findByContentTypeIdOrderBySortOrder(UUID id);

  void deleteByContentTypeId(UUID id);
}
