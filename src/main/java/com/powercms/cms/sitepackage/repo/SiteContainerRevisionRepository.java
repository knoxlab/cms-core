package com.powercms.cms.sitepackage.repo;
import com.powercms.cms.sitepackage.entity.SiteContainerRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SiteContainerRevisionRepository extends JpaRepository<SiteContainerRevisionEntity,UUID>{List<SiteContainerRevisionEntity> findByContainerIdOrderByVersionDesc(UUID containerId);Optional<SiteContainerRevisionEntity> findByContainerIdAndVersion(UUID containerId,int version);}
