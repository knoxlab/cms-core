package com.powercms.cms.search;

import com.powercms.cms.search.entity.SearchIndexMappingStateEntity;
import com.powercms.cms.search.repo.SearchIndexMappingStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 映射基线 / 期望状态的落库出口（FR-MODEL-006 / AC-CM-009）。
 *
 * <p><b>为什么必须独立事务</b>：本类的两个写入方处在语义完全不同的上下文里 ——
 * <ul>
 *   <li>重建与对齐：{@code AdminSearchService.indexAll} 是 {@code @Transactional(readOnly = true)}
 *       （重建只读取内容，不写业务数据）；</li>
 *   <li>模型发布：{@code ContentTypeService} 是读写事务。</li>
 * </ul>
 * 在只读事务里 Hibernate 把 flush 模式降为 {@code MANUAL}，{@code repository.save(...)} 的新实体
 * <b>不会落库 —— 无异常、无告警、无日志</b>。实测后果（域 11 真实栈探针 C3/D2 命中）：
 * <ul>
 *   <li>管理索引的 {@code applied} 基线永远为 {@code NULL} → {@code rebuildRequired} 恒为 true，
 *       「索引需重建」提示永久亮着，运维很快会把它当噪声忽略（告警自身的失效）；</li>
 *   <li>{@code changed} 判定依赖 {@code applied} 非空，于是 {@code search.mapping_stale}
 *       死信与审计**永不触发** —— 属性变更到「需重建」的整条可发现性链路断在这里。</li>
 * </ul>
 * 访客索引因 {@code PublicSearchService.rebuild} 恰好是读写事务而正常落库，症状呈「一半正常」，
 * 是最难被注意到的形态。
 *
 * <p><b>独立事务只包写入、不包指纹计算</b>：模型发布路径必须在调用方事务内计算指纹，
 * 否则 {@code READ_COMMITTED} 下的新事务读不到本次发布尚未提交的字段定义，
 * 期望指纹会永远滞后一次发布。
 */
@Service
public class SearchIndexMappingStateWriter {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexMappingStateWriter.class);

    private final SearchIndexMappingStateRepository repo;

    public SearchIndexMappingStateWriter(SearchIndexMappingStateRepository repo) {
        this.repo = repo;
    }

    /**
     * 落库映射状态（独立事务，REQUIRES_NEW）。
     *
     * <p>{@code entity} 可能来自被挂起的调用方持久化上下文（已游离），因此这里走
     * {@code save} → {@code merge} 语义：按 {@code (site_id, target)} 载入的既有行会被更新，
     * 首次出现则插入。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(SearchIndexMappingStateEntity entity) {
        if (entity == null) return;
        try {
            repo.save(entity);
        } catch (RuntimeException ex) {
            // 基线记录是旁路能力：落库失败不得影响重建/发布主流程（与调用方各自的 try/catch 双保险）
            log.warn("写入索引映射状态失败 site={} target={}：{}",
                entity.getSiteId(), entity.getTarget(), ex.getMessage());
        }
    }
}
