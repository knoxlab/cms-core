package com.powercms.cms.model.repo;

import com.powercms.cms.model.entity.ContentTypeChangeLogEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentTypeChangeLogRepository
    extends JpaRepository<ContentTypeChangeLogEntity, UUID> {
  List<ContentTypeChangeLogEntity> findByContentTypeIdOrderByToVersionAsc(UUID id);

  void deleteByContentTypeId(UUID id);
}
