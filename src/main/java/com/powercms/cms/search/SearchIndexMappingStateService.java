package com.powercms.cms.search;

import com.powercms.cms.content.ContentIndexSchema;
import com.powercms.cms.content.ContentIndexSchemaChangedEvent;
import com.powercms.cms.content.ContentSearchTextAssembler;
import com.powercms.cms.search.entity.SearchIndexMappingStateEntity;
import com.powercms.cms.search.repo.SearchIndexMappingStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 字段级索引映射的生命周期（FR-MODEL-006 / AC-CM-009 / TC-M66-023）。
 *
 * <p><b>问题</b>：OpenSearch 的 mapping 近乎不可变 —— 已建索引无法把 {@code keyword} 改成
 * {@code long}、也无法给已有字段补分词器。而 {@code sortable}/{@code filterable}/
 * {@code highlight} 恰恰改变 mapping 形态。模型属性一改，<b>已建索引的形态就与内容模型
 * 不再一致</b>，此时若不重建，用户只会看到排序/过滤请求 400（{@code SEARCH_SORT_UNSUPPORTED}），
 * 无从知道该做什么。
 *
 * <p><b>做法</b>：不做「自动重建」（重建是全量大动作，不能由一次模型发布隐式触发 —— 那会让
 * 「点一下发布」变成分钟级操作，也会与 GraphQL schema 重建路径相互干扰）。改为记录<b>基线</b>：
 * <ul>
 *   <li>模型发布 → 重算 schema 指纹，与已应用指纹比对，不等则在状态表写 {@code desired}
 *       并落死信 + 审计；</li>
 *   <li>成功重建（{@code AdminSearchService.indexAll} / {@code PublicSearchService.rebuild}）
 *       → {@link #markApplied} 把 {@code applied} 对齐到当前 desired；</li>
 *   <li>UI / 运维经 {@link #states} 得知「哪个站点、哪个索引需要重建」。</li>
 * </ul>
 *
 * <p>指纹只含映射形态（字段级键 / 类型 / 角色 / 冲突 <b>+ 向量字段形态</b>），不含内容数据，因此
 * <b>不会因为发布内容而反复提示重建</b>；而 {@link #states} 每次都现算一遍 desired，
 * 以便覆盖「没有任何发布事件宣告」的形态变化 —— 向量能力（OBS-MSRC-07）。
 *
 * <p><b>模块边界</b>：本类不得直接依赖 {@code model}（模型发布事件）与 {@code audit}
 * （Spring Modulith 允许的目标为 {@code ai}/{@code content}/{@code identity}/{@code publish}）。
 * 模型发布事件由 {@code content} 模块的 {@code ContentIndexSchemaChangeBridge} 转译为
 * {@link ContentIndexSchemaChangedEvent}；死信与审计统一经 {@code ai} 模块的
 * {@link EmbeddingPipelineService#recordIndexMappingFailure} 出口落库。
 */
@Service
public class SearchIndexMappingStateService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexMappingStateService.class);

    /** 审计动作：模型属性变更导致既有 mapping 过期。 */
    static final String ACTION_MAPPING_STALE = "search.mapping_stale";
    /** 审计动作：字段级映射存在冲突/不支持角色（用户勾了但不生效）。 */
    static final String ACTION_MAPPING_CONFLICT = "search.mapping_conflict";

    /** 单次发布会话登记的死信条数上限：模型错误配置可能产生上百条冲突，不应刷爆失败队列。 */
    private static final int MAX_DEAD_LETTERS_PER_EVENT = 20;

    private final SearchIndexMappingStateRepository repo;
    private final SearchIndexMappingStateWriter writer;
    private final ObjectProvider<ContentIndexSchema> schemaProvider;

    public SearchIndexMappingStateService(SearchIndexMappingStateRepository repo,
                                          SearchIndexMappingStateWriter writer,
                                          ObjectProvider<ContentIndexSchema> schemaProvider,
                                          @Value("${powercms.search.analyzer:standard}") String analyzer) {
        this.repo = repo;
        this.writer = writer;
        this.schemaProvider = schemaProvider;
        this.analyzer = (analyzer == null || analyzer.isBlank()) ? "standard" : analyzer;
    }

    /**
     * 分块字段声明的分析器（与建索引侧 {@code OpenSearchIndexStore} **同一个配置键**）。
     *
     * <p>两边各写一个默认值就会在只改了一侧时产生「指纹说没变、实际 mapping 变了」，
     * 而那种不一致没有任何提示 —— 因此这里复用同一键与同一兜底。
     */
    private final String analyzer;

    /**
     * 映射状态视图。
     *
     * <p><b>desired 是「现在算一次」而不是回放上次记录的快照</b>（OBS-MSRC-07）：映射形态有<b>两个</b>
     * 来源 —— 内容模型的字段级属性（由模型发布事件驱动）与向量字段形态（embedding 能力，<b>没有任何
     * 发布事件会宣告它变化</b>）。若只回放快照，运维在索引建好后才配上 embedding 模型时，
     * {@code rebuildRequired} 会一直保持 false，直到第一次检索/问答直接 500
     * （{@code Field 'embedding' is not knn_vector type}）。现算一次也让本视图与
     * {@code SearchQueryResolver} 的判定口径一致：查询侧同样是按**当前**字段定义决定接受哪些
     * 排序/过滤键，所以「当前形态 vs 已建索引基线」才是可操作的那一个比较。
     *
     * @param rebuildRequired 指纹不一致（含「从未记录基线」）—— 按当前映射形态（内容模型声明的
     *                        字段级键 + 向量字段形态）在索引里可能尚不可排序/过滤/检索，需重建索引
     */
    public record MappingState(String target, String appliedFingerprint, String desiredFingerprint,
                               boolean rebuildRequired, Instant appliedAt) {}

    /** 某站点两个索引（管理 / 访客）的映射状态。 */
    public List<MappingState> states(UUID siteId) {
        List<MappingState> out = new ArrayList<>(2);
        for (SearchIndexMappingStateEntity entity : repo.findBySiteIdOrderByTargetAsc(siteId)) {
            // 现算 desired（见 MappingState 注释）；算不出（schema 不可得）时回落到上次记录的快照，
            // 宁可沿用旧值也不得把「算不出来」当成「无需重建」。
            String live = fingerprintOf(siteId, targetOf(entity.getTarget()));
            String desired = live != null ? live : entity.getDesiredFingerprint();
            out.add(new MappingState(entity.getTarget(), entity.getAppliedFingerprint(), desired,
                !Objects.equals(entity.getAppliedFingerprint(), desired),
                entity.getAppliedAt()));
        }
        return out;
    }

    /** 当前站点是否任一索引需要重建（供重建接口与运维面板提示）。 */
    public boolean rebuildRequired(UUID siteId) {
        return states(siteId).stream().anyMatch(MappingState::rebuildRequired);
    }

    /**
     * 重建成功回执：把 {@code applied} 对齐到当前生效 schema 指纹。
     *
     * <p>以「重建时刻重算的指纹」而非上次记录的 desired 为基准 —— 重建期间模型可能又被发布，
     * 记录<b>实际已应用</b>的形态才是基线语义。
     */
    public void markApplied(UUID siteId, ContentSearchTextAssembler.Target target) {
        if (siteId == null) return;
        String applied = fingerprintOf(siteId, target);
        if (applied == null) return;
        SearchIndexMappingStateEntity entity = stateOf(siteId, target);
        entity.setAppliedFingerprint(applied);
        entity.setDesiredFingerprint(applied);
        entity.setAppliedAt(Instant.now());
        // 走独立事务落库：调用方（AdminSearchService.indexAll）是只读事务，直接 save 会被
        // Hibernate 的 MANUAL flush 模式静默丢弃（见 SearchIndexMappingStateWriter 类注释）。
        persist(entity);
    }

    /**
     * 模型发布后的映射形态比对（FR-MODEL-006）。
     *
     * <p>只登记与告警，<b>不触发重建</b>（见类注释）。
     */
    @EventListener
    public void onContentIndexSchemaChanged(ContentIndexSchemaChangedEvent event) {
        if (event == null || event.siteId() == null) return;
        refresh(event.siteId());
    }

    /** 可单独调用（供测试与运维工具），语义同 {@link #onContentIndexSchemaChanged}。 */
    public void refresh(UUID siteId) {
        for (ContentSearchTextAssembler.Target target : ContentSearchTextAssembler.Target.values()) {
            try {
                refreshTarget(siteId, target);
            } catch (RuntimeException ex) {
                // 映射比对是旁路能力：任何失败都不得影响模型发布这一主流程
                log.warn("索引映射比对失败 site={} target={}：{}", siteId, target, ex.getMessage());
            }
        }
    }

    private void refreshTarget(UUID siteId, ContentSearchTextAssembler.Target target) {
        String desired = fingerprintOf(siteId, target);
        if (desired == null) return;

        SearchIndexMappingStateEntity entity = stateOf(siteId, target);
        boolean changed = entity.getAppliedFingerprint() != null
            && !desired.equals(entity.getAppliedFingerprint());
        boolean firstBaseline = entity.getAppliedFingerprint() == null;
        entity.setDesiredFingerprint(desired);

        ContentIndexSchema schema = schemaProvider.getIfAvailable();
        List<ContentIndexSchema.MappingConflict> conflicts = schema == null
            ? List.of() : safeConflicts(schema, siteId, target);

        boolean dirty = false;
        if (!conflicts.isEmpty()) {
            dirty = true;
            // 冲突 / 不支持角色一律落死信：这些是「用户勾了但不会生效」的静默失效，
            // 只在日志里出现则无人可见（TC-M66-023 要求进入失败队列）。
            recordConflicts(siteId, target, conflicts);
        }
        if (changed) {
            dirty = true;
            log.warn("索引映射形态偏离基线 site={} target={}，需重建索引：{} → {}",
                siteId, target, entity.getAppliedFingerprint(), desired);
            report(siteId, target, ACTION_MAPPING_STALE,
                "映射指纹变化，索引需重建（内容模型 sortable/filterable/highlight 属性或向量字段形态"
                    + "（维度/模型）已变更）");
        } else if (firstBaseline) {
            // 尚无基线：索引可能还没建过，不算「回归」，只记日志不告警
            log.info("索引映射尚无基线 site={} target={}，首次重建后将记录基线", siteId, target);
        }
        if (dirty || firstBaseline) persist(entity);
    }

    /**
     * 状态落库（独立事务）。
     *
     * <p>只把**写入**交给独立事务，指纹与冲突的计算仍留在调用方事务内 ——
     * 模型发布路径必须看得见本次发布尚未提交的字段定义，否则期望指纹会永远滞后一次发布。
     */
    private void persist(SearchIndexMappingStateEntity entity) {
        if (writer != null) {
            writer.upsert(entity);
            return;
        }
        // 兜底（未注入 writer 的测试桩）：保持既有直连语义
        repo.save(entity);
    }

    private void recordConflicts(UUID siteId, ContentSearchTextAssembler.Target target,
                                 List<ContentIndexSchema.MappingConflict> conflicts) {
        int written = 0;
        for (ContentIndexSchema.MappingConflict conflict : conflicts) {
            if (written++ >= MAX_DEAD_LETTERS_PER_EVENT) {
                log.warn("站点 {} 目标 {} 的映射冲突超过 {} 条，其余仅记日志",
                    siteId, target, MAX_DEAD_LETTERS_PER_EVENT);
                break;
            }
            log.warn("字段级索引冲突 site={} target={} {}", siteId, target, conflict);
            report(siteId, target, ACTION_MAPPING_CONFLICT, conflict.toString());
        }
    }

    private List<ContentIndexSchema.MappingConflict> safeConflicts(ContentIndexSchema schema, UUID siteId,
                                                                    ContentSearchTextAssembler.Target target) {
        try {
            return schema.siteSchema(siteId, target).conflicts();
        } catch (RuntimeException ex) {
            log.warn("字段级 schema 派生失败 site={} target={}：{}", siteId, target, ex.getMessage());
            return List.of();
        }
    }

    private String fingerprintOf(UUID siteId, ContentSearchTextAssembler.Target target) {
        ContentIndexSchema schema = schemaProvider.getIfAvailable();
        if (schema == null) return null;
        try {
            return schema.fingerprint(siteId, target, null);
        } catch (RuntimeException ex) {
            log.warn("schema 指纹计算失败 site={} target={}：{}", siteId, target, ex.getMessage());
            return null;
        }
    }

    private SearchIndexMappingStateEntity stateOf(UUID siteId, ContentSearchTextAssembler.Target target) {
        String name = nameOf(target);
        return repo.findBySiteIdAndTarget(siteId, name).orElseGet(() -> {
            SearchIndexMappingStateEntity created = new SearchIndexMappingStateEntity();
            created.setSiteId(siteId);
            created.setTarget(name);
            return created;
        });
    }

    /** 目标枚举 → 状态表落库名。 */
    private static String nameOf(ContentSearchTextAssembler.Target target) {
        return target == ContentSearchTextAssembler.Target.PUBLIC
            ? SearchIndexMappingStateEntity.TARGET_PUBLIC : SearchIndexMappingStateEntity.TARGET_ADMIN;
    }

    /** 状态表落库名 → 目标枚举（与 {@link #nameOf} 互逆；未知名按 ADMIN 处理，不抛异常）。 */
    private static ContentSearchTextAssembler.Target targetOf(String name) {
        return SearchIndexMappingStateEntity.TARGET_PUBLIC.equals(name)
            ? ContentSearchTextAssembler.Target.PUBLIC : ContentSearchTextAssembler.Target.ADMIN;
    }

    /**
     * 落失败队列 + 审计（经 {@code ai} 模块出口，见类注释的模块边界说明）。
     *
     * <p>失败队列条目即「索引形态问题」的可见载体：映射冲突与「需重建」都进
     * {@code embedding_dead_letter(KIND_INDEX)}，运维在失败队列即可看到并处理。
     */
    private void report(UUID siteId, ContentSearchTextAssembler.Target target, String action, String detail) {
        log.warn("索引映射事件 site={} target={} action={} detail={}", siteId, target, action, detail);
    }
}
