package com.powercms.cms.delivery.controller;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SiteControllerRepository extends JpaRepository<SiteControllerEntity,UUID>{
 List<SiteControllerEntity> findBySiteIdOrderByPathAsc(UUID siteId);
 Optional<SiteControllerEntity> findByIdAndSiteId(UUID id,UUID siteId);
 Optional<SiteControllerEntity> findBySiteIdAndRoutePathAndHttpMethodAndStatus(UUID siteId,String routePath,String method,String status);
 boolean existsBySiteIdAndRoutePathAndHttpMethodAndIdNot(UUID siteId,String routePath,String method,UUID id);
 boolean existsBySiteIdAndRoutePathAndHttpMethod(UUID siteId,String routePath,String method);
}
