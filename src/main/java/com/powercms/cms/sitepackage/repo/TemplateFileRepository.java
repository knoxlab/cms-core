package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.TemplateFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateFileRepository extends JpaRepository<TemplateFileEntity, UUID> {
    List<TemplateFileEntity> findByTemplateProjectIdOrderByPath(UUID projectId);
    Optional<TemplateFileEntity> findByTemplateProjectIdAndPath(UUID projectId, String path);
}
