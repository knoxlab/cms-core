package com.powercms.cms.stats.repo;

import com.powercms.cms.stats.entity.AnalyticsNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AnalyticsNodeRepository extends JpaRepository<AnalyticsNodeEntity, String> {

    List<AnalyticsNodeEntity> findByLastHeartbeatAtAfter(Instant since);

    @Modifying
    @Query("update AnalyticsNodeEntity n set n.status = :status where n.serverNode = :serverNode")
    int updateStatus(@Param("serverNode") String serverNode, @Param("status") String status);
}
