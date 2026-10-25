package com.powercms.cms.sitepackage.repo;
import com.powercms.cms.sitepackage.entity.SharedComponentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SharedComponentRepository extends JpaRepository<SharedComponentEntity,UUID>{
 List<SharedComponentEntity> findBySiteIdOrderByPathAsc(UUID siteId); Optional<SharedComponentEntity> findBySiteIdAndPath(UUID siteId,String path);
}
