package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.WorkflowTaskEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowTaskRepository extends JpaRepository<WorkflowTaskEntity, UUID> {
    List<WorkflowTaskEntity> findByAssigneeAndStatus(String assignee, String status);
    List<WorkflowTaskEntity> findByContentItemIdOrderByCreatedAtDesc(UUID contentItemId);
    boolean existsByContentItemIdAndStatus(UUID contentItemId, String status);
    List<WorkflowTaskEntity> findByContentItemIdAndStatus(UUID contentItemId, String status);
    List<WorkflowTaskEntity> findByStatus(String status);

    /** 工作台「待我审批」widget：按站点列出全部待审任务，创建时间倒序。 */
    List<WorkflowTaskEntity> findBySiteIdAndStatusOrderByCreatedAtDesc(UUID siteId, String status);
    long countBySiteIdAndStatus(UUID siteId, String status);


    @Query("""
        select t.id from WorkflowTaskEntity t
        where t.status = 'APPROVED'
          and t.executionState is null
          and t.scheduledAt is not null
          and t.scheduledAt <= :now
        """)
    List<UUID> findDueScheduledTaskIds(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WorkflowTaskEntity t where t.id = :id")
    Optional<WorkflowTaskEntity> findByIdForUpdate(@Param("id") UUID id);
}
