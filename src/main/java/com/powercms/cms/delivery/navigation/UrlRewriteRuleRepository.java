package com.powercms.cms.delivery.navigation;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface UrlRewriteRuleRepository extends JpaRepository<UrlRewriteRuleEntity,UUID>{List<UrlRewriteRuleEntity> findBySiteIdOrderByPriorityDescNameAsc(UUID siteId); Optional<UrlRewriteRuleEntity> findByIdAndSiteId(UUID id,UUID siteId); boolean existsBySiteIdAndNameAndIdNot(UUID siteId,String name,UUID id); boolean existsBySiteIdAndName(UUID siteId,String name);}
