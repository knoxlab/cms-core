package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.WorkflowTransitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransitionEntity, UUID> {
    List<WorkflowTransitionEntity> findByWorkflowDefinitionIdOrderBySortOrder(UUID workflowDefId);
    List<WorkflowTransitionEntity> findByWorkflowDefinitionIdAndSourceStatus(UUID wfDefId, String sourceStatus);
    void deleteByWorkflowDefinitionId(UUID wfDefId);
}
