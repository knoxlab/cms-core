package com.powercms.cms.search;

import com.powercms.cms.content.ContentPublishedEvent;
import com.powercms.cms.content.ContentSearchTextAssembler;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 访客索引（FR-SEARCH-002/006/007、FR-API-002）。
 *
 * <p><b>口径统一</b>：人群 = {@link PublicIndexScope}（已部署 Release 成员 ∩ PUBLISHED，
 * 无部署即空）；文档 = {@link AdminSearchService#buildDocument(ContentItemEntity, int,
 * ContentSearchTextAssembler.Target, UUID)}（带 {@code status}/{@code releaseId}/{@code contentVersion}
 * 与字段级键）。本类不再自建文档，也不再自取版本；两条写入路径（全量重建、发布事件增量）共用同一口径，
 * 避免「重建索引」与「重建向量」各写一套形态（此前 Release 重建的文档缺 {@code status}，
 * 与查询期 {@code status=PUBLISHED} 过滤互相打架，重建后匿名检索全空）。
 */
@Service
public class PublicSearchService {
    private final ContentItemRepository itemRepo;
    private final SearchIndexStore store;
    private final String environment;
    /**
     * 部署门禁（FR-SEARCH-007）：用 {@link org.springframework.beans.factory.ObjectProvider} **惰性**持有。
     * {@link IndexSyncGate} 反向依赖本服务（部署时按目标 Release 自愈建索引），构造期取实例会形成循环引用
     * （实测：应用启动 {@code BeanCurrentlyInCreationException}），故仅在重建完成需要登记基线时才解析。
     */
    private final org.springframework.beans.factory.ObjectProvider<IndexSyncGate> indexGateProvider;
    private final ObjectPermissionService objectPermissions;
    private final AdminSearchService adminSearchService;
    /** 访客索引人群唯一真源（FR-SEARCH-002）。 */
    private final PublicIndexScope scope;

    /**
     * 映射基线记录（FR-MODEL-006）：可选依赖，未注入时重建不回执基线（单测桩场景）。
     */
    private volatile SearchIndexMappingStateService mappingState;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMappingState(SearchIndexMappingStateService mappingState) {
        this.mappingState = mappingState;
    }

    public record SearchResult(UUID contentId, String title) {}

    PublicSearchService(ContentItemRepository itemRepo, SearchIndexStore store,
                        @Value("${spring.profiles.active:default}") String environment,
                        org.springframework.beans.factory.ObjectProvider<IndexSyncGate> indexGate,
                        ObjectPermissionService objectPermissions,
                        AdminSearchService adminSearchService,
                        PublicIndexScope scope) {
        this.itemRepo = itemRepo;
        this.store = store;
        this.environment = environment;
        this.indexGateProvider = indexGate;
        this.objectPermissions = objectPermissions;
        this.adminSearchService = adminSearchService;
        this.scope = scope;
    }

    /**
     * 按**当前已部署 Release** 全量重建访客索引（FR-SEARCH-006：候选索引 + 别名原子切换）。
     *
     * <p>无已部署 Release 时人群为空 → 重建出**空**访客索引：这是规格口径的正确结果
     * （FR-SEARCH-002「只包含当前已部署 Release 的公开内容」，与交付读取 {@code DeliveryService}
     * 在无部署时返回空集合一致），不再把「全站发布态内容」当成访客可见集。
     */
    @Transactional
    public void rebuild(UUID siteId) {
        rebuild(scope.deployed(siteId), siteId);
    }

    /**
     * 按**指定 Release** 建立并切换访客索引（FR-SEARCH-007 门禁自愈入口）。
     *
     * <p>用途：部署流程要求「访客索引 releaseId == 目标 Release」，但 {@link #rebuild} 只能按
     * **已部署** Release 建 —— 严格口径下首次部署永远无法满足门禁（鸡生蛋）。部署事件到达时由
     * {@link IndexSyncGate} 调用本方法按**待部署 Release** 建立索引，从而打通首次部署。
     *
     * <p><b>{@code REQUIRES_NEW} 不是可选项</b>：本方法的唯一调用点在
     * {@link IndexSyncGate} 的 {@code @TransactionalEventListener(AFTER_COMMIT)} 回调里。Spring 在该
     * 回调中**仍把已提交事务的资源绑定在当前线程上**（见 {@code TransactionSynchronization#afterCommit}
     * 的官方说明：此处的数据访问仍会「参与」原事务，而原事务已提交、之后不再有提交动作），
     * 因此默认传播（REQUIRED）下的写库会**静默丢失**——实测症状：部署自愈把访客索引建好、别名也切了，
     * 但 {@code site_index_status.last_release_id} 没有落库，门禁随即判定「仍不同步」并回退部署。
     * 必须显式开新事务，让索引基线的登记独立提交。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void rebuildForRelease(UUID siteId, UUID releaseId) {
        rebuild(scope.forRelease(siteId, releaseId), siteId);
    }

    private void rebuild(PublicIndexScope.Scope scopeSnapshot, UUID siteId) {
        List<SearchIndexStore.SearchDocument> documents = new ArrayList<>();
        for (PublicIndexScope.Entry entry : scopeSnapshot.entries()) {
            Optional<ContentItemEntity> item = itemRepo.findById(entry.contentItemId())
                .filter(found -> siteId.equals(found.getSiteId()));
            if (item.isEmpty()) continue;
            // 成员版本钉住（不是 currentVersion）：索引正文必须与 Release 对外交付的版本一致
            SearchIndexStore.SearchDocument document = adminSearchService.buildDocument(item.get(),
                entry.version(), ContentSearchTextAssembler.Target.PUBLIC, scopeSnapshot.releaseId());
            if (document != null) documents.add(document);
        }
        store.rebuild(indexName(siteId), candidateName(siteId), documents,
            adminSearchService.currentMapping(siteId, ContentSearchTextAssembler.Target.PUBLIC));
        // FR-MODEL-006：访客索引的字段级键按 publicIndex 派生，重建成功后单独回执该目标的映射基线
        recordMappingApplied(siteId);
        // FR-SEARCH-007：登记本次索引对应的 releaseId 供部署门禁比对；无部署（空人群）时不登记，
        // 门禁会因此判定「未就绪」并走自愈（而不是把 null 当成同步）。
        IndexSyncGate indexGate = indexGateProvider == null ? null : indexGateProvider.getIfAvailable();
        if (indexGate != null && scopeSnapshot.releaseId() != null) {
            indexGate.recordRebuild(siteId, indexName(siteId), scopeSnapshot.releaseId(), documents.size());
        }
    }

    /**
     * 访客检索（FR-SEARCH-004）。
     *
     * <p><b>严格口径兜底（FR-SEARCH-002 / FR-API-002）</b>：站点**无已部署 Release** 时直接返回空集，
     * 与交付读取同口径。索引是**可以被别的路径写坏的物化视图**（曾实测：一次「重建向量」把全站草稿
     * 灌进访客索引并切了别名，匿名就搜到了草稿正文），因此除索引侧人群收敛外，查询期还有两闸门：
     * ①无部署 → 空；②{@code status=PUBLISHED} 过滤；③对象级 ACL（{@code hasAnonymousRight}）。
     */
    public List<SearchResult> search(UUID siteId, String query) {
        if (scope.deployedReleaseId(siteId) == null) {
            return List.of(); // 无已部署 Release：访客索引不应有任何可交付内容
        }
        Map<String, Object> filters = Map.of(
            "siteId", siteId.toString(),
            "status", ContentItemEntity.STATUS_PUBLISHED);
        return store.search(indexName(siteId), query, filters, 200).stream()
            .filter(hit -> objectPermissions.hasAnonymousRight(
                ObjectType.CONTENT, UUID.fromString(hit.id()), ObjectType.VIEW))
            .map(hit -> new SearchResult(UUID.fromString(hit.id()), String.valueOf(hit.fields().get("title"))))
            .toList();
    }

    /**
     * 发布事件 → 访客索引增量（FR-AI-RAG-001「内容发布时将向量同步到访客索引」）。
     *
     * <p><b>口径闸门（FR-SEARCH-002）</b>：只有**已部署 Release 的成员**才允许写访客索引，且必须写
     * **成员版本**——内容发布（状态 → PUBLISHED）不等于对外交付：未进 Release 的内容交付读取也拿不到
     * （{@code DeliveryService} 按 Release 成员判定），此时写进访客索引就是「索引 ⊋ 交付可见集」。
     * 此前本方法不看 Release，任何内容发布都会把该条（含其 currentVersion 正文）写进访客索引。
     *
     * <p>**DEF-SEARCH-02**：正文按 {@link ContentSearchTextAssembler.Target#PUBLIC}（{@code publicIndex}）
     * 抽取，不得复用管理索引文档，否则未开访客索引的字段会随正文与向量进入访客索引。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPublished(ContentPublishedEvent event) {
        if (event == null || event.contentItemId() == null) return;
        Optional<PublicIndexScope.Entry> member = scope.deployedEntry(event.siteId(), event.contentItemId());
        if (member.isEmpty()) return; // 不在已部署 Release 内：交付不可见，索引也不得写入
        itemRepo.findById(event.contentItemId()).ifPresent(item -> {
            if (!Objects.equals(item.getSiteId(), event.siteId())) return;
            SearchIndexStore.SearchDocument document = adminSearchService.buildDocument(item,
                member.get().version(), ContentSearchTextAssembler.Target.PUBLIC,
                scope.deployedReleaseId(event.siteId()));
            if (document != null) store.upsert(indexName(event.siteId()), document);
        });
    }

    private String indexName(UUID siteId) {
        return "powercms-public-" + environment + "-" + siteId.toString().replace("-", "");
    }

    /**
     * 映射基线回执（FR-MODEL-006）。映射基线是旁路能力：未注入（单测桩）或写入失败
     * 都不得影响重建本身，故在此吞掉异常并降级为日志。
     */
    private void recordMappingApplied(UUID siteId) {
        SearchIndexMappingStateService state = mappingState;
        if (state == null) return;
        try {
            state.markApplied(siteId, ContentSearchTextAssembler.Target.PUBLIC);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(PublicSearchService.class)
                .warn("记录访客索引映射基线失败 site={}：{}", siteId, ex.getMessage());
        }
    }

    private String candidateName(UUID siteId) {
        return indexName(siteId) + "-candidate-" + UUID.randomUUID().toString().replace("-", "");
    }
}
