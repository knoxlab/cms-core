package com.powercms.cms.sitepackage.repo;
import com.powercms.cms.sitepackage.entity.SharedComponentRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SharedComponentRevisionRepository extends JpaRepository<SharedComponentRevisionEntity,UUID>{
 List<SharedComponentRevisionEntity> findByComponentIdOrderByVersionDesc(UUID id); Optional<SharedComponentRevisionEntity> findByComponentIdAndVersion(UUID id,int version); void deleteByComponentId(UUID id);
}
