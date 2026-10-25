package com.powercms.cms.model.repo;

import com.powercms.cms.model.entity.ContentTypeLifecycleEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentTypeLifecycleRepository extends JpaRepository<ContentTypeLifecycleEntity, UUID> {
  List<ContentTypeLifecycleEntity> findByContentTypeIdOrderByEvent(UUID id);
  Optional<ContentTypeLifecycleEntity> findByContentTypeIdAndEvent(UUID id, String event);
  void deleteByContentTypeId(UUID id);
}
