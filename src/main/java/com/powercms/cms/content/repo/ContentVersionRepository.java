package com.powercms.cms.content.repo;

import com.powercms.cms.content.entity.ContentVersionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentVersionRepository extends JpaRepository<ContentVersionEntity, UUID> {
    List<ContentVersionEntity> findByContentItemIdOrderByVersionDesc(UUID contentItemId);
    Optional<ContentVersionEntity> findByContentItemIdAndVersion(UUID contentItemId, int version);

    /** 工作台「My Activity」数据源：当前用户最近保存的内容版本。 */
    List<ContentVersionEntity> findTop20ByCreatedByOrderByCreatedAtDesc(String createdBy, Pageable pageable);

    /**
     * 列表预览图解析：批量取这批条目各自的最新版本（一条查询，避免逐条查版本造成 N+1）。
     * 版本号在条目内单调递增，故最新版本即当前生效版本。
     */
    @Query("""
        select v from ContentVersionEntity v
        where v.contentItemId in :itemIds
          and v.version = (select max(x.version) from ContentVersionEntity x where x.contentItemId = v.contentItemId)
        """)
    List<ContentVersionEntity> findLatestByContentItemIdIn(@Param("itemIds") Collection<UUID> itemIds);

    /**
     * FR-CV-VER-009 时间机器：取出这批内容条目**在 {@code at} 时刻之前（含）已存在的全部版本**，
     * 按条目分组、版本号倒序——调用方取每组第一行即「该时刻生效的快照」。
     *
     * <p>不直接用「max(version) group by」聚合：一次取回明细后由内存分组，语义（同一条目同版本号唯一，
     * 见 {@code content_version} 的唯一约束）无需再依赖子查询写法在各数据库上的一致性；站点级时间机器
     * 的读取范围本就有界（单站点条目数 × 版本数）。</p>
     */
    @Query("""
        select v from ContentVersionEntity v
        where v.contentItemId in :itemIds
          and v.createdAt <= :at
        order by v.contentItemId asc, v.version desc
        """)
    List<ContentVersionEntity> findVersionsNotAfter(@Param("itemIds") Collection<UUID> itemIds,
                                                    @Param("at") Instant at);
}
