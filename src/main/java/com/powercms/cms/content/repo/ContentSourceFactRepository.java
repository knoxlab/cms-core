package com.powercms.cms.content.repo;

import com.powercms.cms.content.entity.ContentSourceFactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** FR-AI-SRC-004 统一来源事实仓储。 */
public interface ContentSourceFactRepository extends JpaRepository<ContentSourceFactEntity, UUID> {

    List<ContentSourceFactEntity> findBySiteId(UUID siteId);

    List<ContentSourceFactEntity> findByContentItemIdIn(Collection<UUID> contentItemIds);

    /**
     * 同步幂等锚点：按「来源系统 + 外部对象 ID」反查已落点的内容条目。
     *
     * <p>同一外部对象在修复前的不幂等同步中可能留下多条落点（各自一条来源事实），
     * 故按 {@code syncedAt} 升序返回最早登记的一批，由调用方挑选仍然存在的条目。
     */
    @Query("""
        select f from ContentSourceFactEntity f
        where f.siteId = :siteId
          and f.sourceSystem = :sourceSystem
          and f.sourceObjectId = :sourceObjectId
        order by f.syncedAt asc, f.contentItemId asc
        """)
    List<ContentSourceFactEntity> findBySourceKey(@Param("siteId") UUID siteId,
                                                  @Param("sourceSystem") String sourceSystem,
                                                  @Param("sourceObjectId") String sourceObjectId);
}
