package com.powercms.cms.delivery.controller;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SiteControllerVersionRepository extends JpaRepository<SiteControllerVersionEntity,UUID>{
 Optional<SiteControllerVersionEntity> findByControllerIdAndVersion(UUID controllerId,int version);
 List<SiteControllerVersionEntity> findByControllerIdOrderByVersionDesc(UUID controllerId);
}
