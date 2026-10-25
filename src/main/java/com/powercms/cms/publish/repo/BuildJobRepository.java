package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.BuildJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BuildJobRepository extends JpaRepository<BuildJobEntity, UUID> {
    List<BuildJobEntity> findByTemplateProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** License 用量水位：每日构建次数。 */
    long countByCreatedAtAfter(Instant since);

    /** C4 构建并发门禁：当前处于指定状态的任务数（BUILD_CONCURRENCY 水位统计）。 */
    long countByStatus(String status);
}
