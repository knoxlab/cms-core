package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.WorkflowDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, UUID> {
    List<WorkflowDefinitionEntity> findBySiteId(UUID siteId);
    List<WorkflowDefinitionEntity> findBySiteIdAndStatus(UUID siteId, String status);
    boolean existsBySiteIdAndNameAndStatus(UUID siteId, String name, String status);
}
