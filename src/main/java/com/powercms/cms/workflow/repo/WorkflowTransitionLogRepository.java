package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.WorkflowTransitionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowTransitionLogRepository extends JpaRepository<WorkflowTransitionLogEntity, Long> {
    List<WorkflowTransitionLogEntity> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
    List<WorkflowTransitionLogEntity> findByContentItemIdOrderByCreatedAtDesc(UUID contentItemId);
}
