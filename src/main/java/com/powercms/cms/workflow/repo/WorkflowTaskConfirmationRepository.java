package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.WorkflowTaskConfirmationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowTaskConfirmationRepository extends JpaRepository<WorkflowTaskConfirmationEntity, UUID> {
    long countByTaskIdAndActionKey(UUID taskId, String actionKey);
    boolean existsByTaskIdAndActionKeyAndActor(UUID taskId, String actionKey, String actor);
    List<WorkflowTaskConfirmationEntity> findByTaskIdAndActionKeyOrderByConfirmedAtAsc(UUID taskId, String actionKey);
}
