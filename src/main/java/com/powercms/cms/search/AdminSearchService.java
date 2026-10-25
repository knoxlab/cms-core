package com.powercms.cms.search;

import com.powercms.cms.content.ContentIndexSchema;
import com.powercms.cms.content.ContentSearchTextAssembler;
import com.powercms.cms.content.FieldIndexSpec;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AdminSearchService {
    private static final Logger log = LoggerFactory.getLogger(AdminSearchService.class);
    private final ContentItemRepository itemRepo;
    private final ContentVersionRepository versionRepo;
    private final ContentSearchTextAssembler textAssembler;
    private final SearchIndexStore store;
    private final String environment;

    /**
     * 字段级索引 schema 派生（AC-CM-009 / FR-MODEL-006）。可选依赖：未注入（单测桩）时
     * 字段级键整体不生成，行为与落地前完全一致（仅 title/body 可检索）。
     */
    private volatile ContentIndexSchema contentIndexSchema;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContentIndexSchema(ContentIndexSchema contentIndexSchema) {
        this.contentIndexSchema = contentIndexSchema;
    }

    /**
     * 映射基线记录（FR-MODEL-006）。可选依赖：单测桩未注入时重建不回执基线。
     */
    private volatile SearchIndexMappingStateService mappingState;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMappingState(SearchIndexMappingStateService mappingState) {
        this.mappingState = mappingState;
    }

    /**
     * 内容文档配额的独立闸门（矩阵 G1/G2「检索侧独立校验」）。可选依赖：单测桩未注入时跳过。
     *
     * <p>放在 {@code content} 模块而不是 {@code platform}，是为了让公开表单提交、媒资同步
     * 这些同样会新增内容文档的写入口也能复用同一道闸门（form/media → content 已是允许依赖），
     * 而不必为检索模块单独开一条 platform 依赖。</p>
     */
    private volatile com.powercms.cms.content.ContentDocumentQuotaGuard documentQuotaGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDocumentQuotaGuard(com.powercms.cms.content.ContentDocumentQuotaGuard documentQuotaGuard) {
        this.documentQuotaGuard = documentQuotaGuard;
    }

    public AdminSearchService(ContentItemRepository itemRepo, ContentVersionRepository versionRepo,
                              ContentSearchTextAssembler textAssembler,
                              SearchIndexStore store, @Value("${spring.profiles.active:default}") String environment) {
        this.itemRepo = itemRepo; this.versionRepo = versionRepo; this.textAssembler = textAssembler; this.store = store; this.environment = environment;
    }

    public record SearchResult(UUID contentId, String title, String status, UUID categoryId) {}

    /**
     * 全量重建（G1）：分批按 content item 抓取，纯关键词（BM25）写入影子索引并切换 alias。
     */
    @Transactional(readOnly = true)
    public void indexAll(UUID siteId) {
        // AC-CM-009：schema 按站点整体派生一次（跨内容类型并集 + 冲突检测），逐条复用，
        // 避免「每篇内容都重算一遍全站字段定义」的 N×M 查询。
        ContentIndexSchema.SiteSchema schema = siteFieldSchema(siteId, ContentSearchTextAssembler.Target.ADMIN);
        List<SearchIndexStore.SearchDocument> docs = new ArrayList<>();
        itemRepo.findAll().stream()
            .filter(i -> siteId.equals(i.getSiteId()))
            .forEach(item -> docs.add(buildDocument(item, ContentSearchTextAssembler.Target.ADMIN, schema)));
        store.rebuild(indexName(siteId), candidateName(siteId), docs, currentMapping(siteId, schema));
        // FR-MODEL-006：记录本次实际生效的映射形态，供「模型属性变更 → 需重建」的比对
        markMappingApplied(siteId, ContentSearchTextAssembler.Target.ADMIN);
        // 检索侧独立校验（矩阵 G1/G2，社区 ≤10 万内容文档）：重建后按索引文档条数核对授权上限。
        // 只告警不阻断——检索可用性不得受授权影响（§1.4 规则 6）。
        observeIndexBaseline(docs.size());
    }

    /** 检索侧独立观测：闸门未注入（单测桩/模块裁剪）时静默跳过。 */
    private void observeIndexBaseline(long indexedContentDocs) {
        com.powercms.cms.content.ContentDocumentQuotaGuard guard = documentQuotaGuard;
        if (guard == null) return;
        guard.observeIndexBaseline(indexedContentDocs);
    }

    @Transactional
    public void indexItem(UUID contentId) {
        itemRepo.findById(contentId).ifPresent(item -> store.upsert(indexName(item.getSiteId()),
            buildDocument(item)));
    }

    public List<SearchResult> search(UUID siteId, String query, Set<String> statuses) {
        return store.search(indexName(siteId), query, Map.of("status", statuses), 200).stream()
            .map(this::result).toList();
    }

    /**
     * 分页检索（FR-SEARCH-004）：排序 + 字段级过滤 + 游标分页 + 属性驱动高亮。
     *
     * <p>{@code query} 必须由 {@link SearchQueryResolver} 产出（字段级键已按内容模型属性校验）。
     */
    public SearchPage searchPage(UUID siteId, SearchQuery query) {
        return store.searchPage(indexName(siteId), query);
    }

    /**
     * 公开给 PublicSearchService：把已带 embedding 的文档写入访客索引（FR-AI-RAG-001：发布时同步）。
     * 文档必须由本服务 {@link #buildDocument(ContentItemEntity, boolean,
     * ContentSearchTextAssembler.Target)} 以 {@code Target.PUBLIC} 生成，以保证正文与向量均为
     * **访客口径**（不得复用管理索引文档，DEF-SEARCH-02）。
     */
    public void writePublicDocument(SearchIndexStore.SearchDocument document) {
        store.upsert(publicIndexName(UUID.fromString(document.fields().get("siteId").toString())), document);
    }

    public void deletePublicDocument(UUID siteId, UUID contentId) {
        store.delete(publicIndexName(siteId), contentId.toString());
    }

    public void deleteAdminDocument(UUID siteId, UUID contentId) {
        store.delete(indexName(siteId), contentId.toString());
    }

    /** Test seam: write documents directly under an explicit alias. */
    public void rebuild(String alias, List<SearchIndexStore.SearchDocument> documents) {
        UUID siteId = siteIdFromIndex(alias);
        ContentSearchTextAssembler.Target target = alias.contains("-public-")
            ? ContentSearchTextAssembler.Target.PUBLIC : ContentSearchTextAssembler.Target.ADMIN;
        store.rebuild(alias, alias + "-candidate-" + UUID.randomUUID().toString().replace("-", ""),
            documents, currentMapping(siteId, target));
        markMappingApplied(siteId, target);
    }

    /**
     * 映射基线回执（FR-MODEL-006）。可选依赖（单测桩未注入时跳过）。
     */
    private void markMappingApplied(UUID siteId, ContentSearchTextAssembler.Target target) {
        SearchIndexMappingStateService state = mappingState;
        if (state == null || siteId == null) return;
        try {
            state.markApplied(siteId, target);
        } catch (RuntimeException ex) {
            log.warn("记录索引映射基线失败 site={} target={}：{}", siteId, target, ex.getMessage());
        }
    }

    // ==== G9-⑩ 分批重建 / 断点续跑 / 协作式取消的影子索引原语 ====

    /**
     * 影子索引名（G9-⑩）：全新重建生成新名字；**断点续跑复用进度表已持久化的名字**，
     * 因此续跑不会丢弃已写入批次（不得为新名字调用 {@link #resetRebuildIndex}）。
     */
    public String newRebuildCandidateIndex(UUID siteId) {
        return publicIndexName(siteId) + "-candidate-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 重置候选索引（含 k-NN mapping 与字段级键）——**仅全新重建调用**；续跑调用会清空已写入批次。 */
    public void resetRebuildIndex(UUID siteId, String candidateIndex) {
        // 本原语的落点是**访客索引**（switchRebuildAlias → publicIndexName），因此字段级键必须按
        // publicIndex 口径派生，否则映射里会出现只有管理索引才该有的字段键。
        store.resetIndex(candidateIndex, currentMapping(siteId, ContentSearchTextAssembler.Target.PUBLIC));
    }

    /** 分批写入候选索引（G9-⑩）：不切换 alias，供逐批次写入 + 游标推进。 */
    public void writeRebuildBatch(String candidateIndex, List<SearchIndexStore.SearchDocument> documents) {
        store.writeBatch(candidateIndex, documents);
    }

    /** 候选索引是否仍存在（G9-⑩ 续跑前置校验；memory 后端进程重启后为 false → 退回全新重建）。 */
    public boolean rebuildIndexExists(String candidateIndex) {
        return store.indexExists(candidateIndex);
    }

    /**
     * 索引内文档条数（DEF-KB-01）：过滤语义与检索完全一致（term / terms / prefix），
     * 供「切别名前的合法性校验」这类只需要一纸计数的场景使用（不读文档体，也不受关键词匹配影响）。
     */
    public long countDocuments(String indexOrAlias, Map<String, Object> filters) {
        return store.count(indexOrAlias, filters == null ? Map.of() : filters);
    }

    /** 候选索引原子切换为访客 alias 并清理旧物理索引（G9-⑩：全部批次写入后调用一次）。 */
    public void switchRebuildAlias(UUID siteId, String candidateIndex) {
        store.switchAlias(publicIndexName(siteId), candidateIndex);
    }

    /**
     * 构造一条带 embedding 的搜索文档（FR-AI-RAG-001，**管理索引**口径）。
     *
     * <p>R6：拼接规则对齐规划「标题+摘要+正文+标签」——由 {@link ContentSearchTextAssembler} 按字段定义
     * 从 fieldValues 抽取干净文本（HTML/Markdown 剥离、空白压缩），按字段的 {@code adminIndex}
     * （§5.3「字段值进入管理端全文索引」，DEF-SEARCH-02）剔除未开管理索引的字段后再入库与向量化；
     * 组装器不可用（测试桩）时降级为原始 fieldValues（向后兼容）。
     *
     * <p>写入**访客索引**请用 {@link #buildDocument(ContentItemEntity, boolean,
     * ContentSearchTextAssembler.Target)} 并显式传 {@code Target.PUBLIC}，不要复用本重载。
     */
    public SearchIndexStore.SearchDocument buildDocument(ContentItemEntity item) {
        return buildDocument(item, ContentSearchTextAssembler.Target.ADMIN);
    }

    /**
     * 构造一条带 embedding 的搜索文档（FR-AI-RAG-001）。
     *
     * <p>R6：拼接规则对齐规划「标题+摘要+正文+标签」——由 {@link ContentSearchTextAssembler} 按字段定义
     * 从 fieldValues 抽取干净文本（HTML/Markdown 剥离、空白压缩），剔除 <b>未开目标索引</b>的字段后再入库
     * 与向量化；组装器不可用（测试桩）时降级为原始 fieldValues（向后兼容）。
     *
     * <p><b>DEF-SEARCH-02</b>：{@code target} 决定按哪个字段属性过滤——{@link
     * ContentSearchTextAssembler.Target#ADMIN} 按 {@code adminIndex}（管理索引）、
     * {@link ContentSearchTextAssembler.Target#PUBLIC} 按 {@code publicIndex}（访客索引）。
     * **访客索引必须传 {@code PUBLIC}**：否则 {@code adminIndex=true} 但未开访客索引的字段（含敏感字段，
     * 二者在模型校验中互斥于 publicIndex 侧）会随正文与向量一起写入访客索引。
     */
    public SearchIndexStore.SearchDocument buildDocument(ContentItemEntity item,
                                                        ContentSearchTextAssembler.Target target) {
        ContentSearchTextAssembler.Target effective =
            target == null ? ContentSearchTextAssembler.Target.ADMIN : target;
        return buildDocument(item, effective, siteFieldSchema(item.getSiteId(), effective));
    }

    /**
     * 构造一条**指定版本 + 指定 Release** 的搜索文档（FR-SEARCH-003 / 访客索引口径统一）。
     *
     * <p>新增本重载的原因：访客索引人群由 {@link PublicIndexScope} 统一为
     * 「已部署 Release 成员 ∩ PUBLISHED」，其成员是**版本钉住**的（Release 冻结时的
     * {@code publishedVersion}），而条目本体可能已经有更新的草稿版本。此前访客索引的两条写路径
     * 都取 {@code item.getCurrentVersion()}：一旦内容在发布后又被编辑，索引里的正文/向量就与该
     * Release 对外交付的版本**不是同一份**，且文档缺 {@code releaseId}，违背 FR-SEARCH-003
     * 「文档必须包含 contentVersion、releaseId」。
     *
     * @param version   文档代表的版本（Release 成员版本 / 事件版本）
     * @param releaseId 口径来源的 Release；{@code null} 表示非 Release 口径（管理索引或降级路径）
     */
    public SearchIndexStore.SearchDocument buildDocument(ContentItemEntity item, int version,
                                                        ContentSearchTextAssembler.Target target,
                                                        UUID releaseId) {
        ContentSearchTextAssembler.Target effective =
            target == null ? ContentSearchTextAssembler.Target.ADMIN : target;
        String raw = versionRepo.findByContentItemIdAndVersion(item.getId(), version)
            .map(ContentVersionEntity::getFieldValues).orElse("");
        return buildDocument(item, version, raw, effective,
            siteFieldSchema(item.getSiteId(), effective), releaseId);
    }

    /**
     * 构造一条带 embedding 的搜索文档（已具备预先派生的字段级 schema —— 批量路径用）。
     *
     * <p>AC-CM-009 / FR-MODEL-006：{@code schema} 决定哪些 {@code sortable}/{@code filterable}/
     * {@code highlight} 字段以 {@code f_<code>} 独立键写入文档。**只写 schema 里存在的键**：冲突被
     * 剔除的键若仍写入，会与 mapping 声明的类型不符，被 OpenSearch bulk 整体拒绝
     * （{@code requireBulkOk} 直接抛错 → 整次重建失败）。
     *
     * <p>字段级键的取值随之受**目标索引闸门**约束（{@link ContentIndexSchema#resolve}）：未开
     * {@code publicIndex} 的字段不会以 {@code f_*} 形式把原值带进访客索引。
     */
    public SearchIndexStore.SearchDocument buildDocument(ContentItemEntity item,
                                                        ContentSearchTextAssembler.Target target,
                                                        ContentIndexSchema.SiteSchema schema) {
        String raw = versionRepo.findByContentItemIdAndVersion(item.getId(), item.getCurrentVersion())
            .map(ContentVersionEntity::getFieldValues).orElse("");
        return buildDocument(item, item.getCurrentVersion(), raw, target, schema, null);
    }

    /**
     * 文档装配唯一实现（版本 / 字段值 / Release 显式传入，避免各写路径各取一套版本口径）。
     *
     * @param version   写入 {@code contentVersion} 的版本号
     * @param raw       该版本的原始 {@code fieldValues}
     * @param releaseId 口径来源 Release（{@code null} 不写 {@code releaseId} 键）
     */
    private SearchIndexStore.SearchDocument buildDocument(ContentItemEntity item, int version, String raw,
                                                        ContentSearchTextAssembler.Target target,
                                                        ContentIndexSchema.SiteSchema schema,
                                                        UUID releaseId) {
        String body;
        String combined;
        if (textAssembler == null) {
            body = raw;
            combined = (item.getTitle() == null ? "" : item.getTitle()) + "\n" + raw;
        } else {
            // DEF-SEARCH-02：按目标索引的字段属性过滤——管理索引 {@code adminIndex}、访客索引 {@code publicIndex}
            // （此前两侧共用 publicIndex，使 adminIndex 彻底失效、管理索引正文恒为空：正文关键词搜不到、
            // snapshot 恒空、管理侧向量输入退化为仅标题）。敏感字段隔离由 publicIndex（访客侧）
            // 与对象级 ACL（管理侧，FR-AI-RAG-006）承载。
            ContentSearchTextAssembler.IndexText text = textAssembler.assemble(
                item.getContentTypeId(), item.getTitle(), raw,
                target == null ? ContentSearchTextAssembler.Target.ADMIN : target);
            body = text.content();
            combined = text.embeddingText();
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("siteId", item.getSiteId().toString());
        // FR-SEARCH-004：contentId 同时是游标分页的 tiebreaker（mapping 里已声明 keyword），
        // 必须与 _id 一致写入 —— 排序取不到值会让排序键恒为空、翻页顺序退化。
        fields.put("contentId", item.getId().toString());
        if (item.getCategoryId() != null) fields.put("categoryId", item.getCategoryId().toString());
        fields.put("contentTypeId", item.getContentTypeId().toString());
        fields.put("title", item.getTitle());
        fields.put("status", item.getStatus());
        fields.put("body", body);
        fields.put("currentVersion", item.getCurrentVersion());
        // FR-SEARCH-003：文档必须能追到权威内容版本。contentVersion = **本档代表的版本**
        // （Release 成员版本 / 事件版本），currentVersion 保留为「条目当前版本」——
        // 二者在「发布后又被编辑」时不同，混用会让访客索引交付的不是 Release 对外的那一份。
        fields.put("contentVersion", version);
        // FR-SEARCH-003：口径来源 Release。缺失即代表该文档不由 Release 口径产生（管理索引/降级路径）。
        if (releaseId != null) fields.put("releaseId", releaseId.toString());
        // FR-AI-SRC-003：来源域标注（CMS / DAM:<端点> / PIM:<数据源>），联合检索命中透出。
        fields.put("sourceDomain", item.getSourceDomain());
        if (item.getRoutePath() != null) fields.put("routePath", item.getRoutePath());
        if (item.getUpdatedAt() != null) fields.put("updatedAt", item.getUpdatedAt().toString());
        // FR-AI-RAG-003：可见 group 集合。默认 "public" 即可被匿名访客命中。
        fields.put("_visibleGroups", visibleGroupsFor(item));
        // AC-CM-009：字段级索引键（sortable/filterable/highlight）——类型保真写入
        if (contentIndexSchema != null && schema != null) {
            fields.putAll(contentIndexSchema.valuesFor(schema, item.getContentTypeId(), raw));
        }

        return new SearchIndexStore.SearchDocument(item.getId().toString(), fields);
    }

    public SearchIndexStore.IndexMapping currentMapping(UUID siteId) {
        return currentMapping(siteId, ContentSearchTextAssembler.Target.ADMIN);
    }

    /**
     * 目标索引对应的 mapping（含字段级键，AC-CM-009）。
     *
     * <p>{@code target} 决定字段级键的集合：管理索引取 {@code adminIndex} 字段、访客索引取
     * {@code publicIndex} 字段 —— 两侧 mapping 因此不同，**不可互换**（互换会让访客索引多出本不该
     * 存在的字段键，或让管理索引的排序键缺失）。
     */
    public SearchIndexStore.IndexMapping currentMapping(UUID siteId, ContentSearchTextAssembler.Target target) {
        return currentMapping(siteId, siteFieldSchema(siteId, target));
    }

    private SearchIndexStore.IndexMapping currentMapping(UUID siteId, ContentIndexSchema.SiteSchema schema) {
        List<FieldIndexSpec> specs = schema == null ? List.of() : schema.specs();
        // 社区版检索为纯 BM25：无向量字段，只声明字段级索引键。
        return new SearchIndexStore.IndexMapping(specs);
    }

    /** 站点在指定目标索引上的字段级 schema（AC-CM-009）；组装器/仓储不可用时返回空 schema。 */
    public ContentIndexSchema.SiteSchema siteFieldSchema(UUID siteId, ContentSearchTextAssembler.Target target) {
        ContentIndexSchema schema = contentIndexSchema;
        if (schema == null || siteId == null) return ContentIndexSchema.SiteSchema.empty();
        try {
            return schema.siteSchema(siteId, target);
        } catch (RuntimeException ex) {
            log.warn("字段级索引 schema 派生失败 siteId={}，退化为仅 title/body 可检索：{}", siteId, ex.getMessage());
            return ContentIndexSchema.SiteSchema.empty();
        }
    }

    /**
     * FR-AI-RAG-003：内容可见 group 标签解析（索引侧预筛）。
     *
     * <p>社区版不提供对象级 ACL 与内容分级：公开内容统一贴 {@code ["public"]} 标签，
     * 访客索引仅收录已部署 Release 的公开内容（{@link PublicIndexScope}）。</p>
     */
    List<String> visibleGroupsFor(ContentItemEntity item) {
        if (item == null) return List.of("public");
        return List.of("public");
    }

    /** categoryId 等字段在索引文档中可能缺失，不可用 UUID.fromString(String.valueOf(null)) 硬解析。 */
    private SearchResult result(SearchIndexStore.SearchHit hit) {
        return new SearchResult(safeUuid(hit.id()), text(hit.fields().get("title")),
            text(hit.fields().get("status")), safeUuid(hit.fields().get("categoryId")));
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }

    private static UUID safeUuid(Object value) {
        if (value == null) return null;
        try { return UUID.fromString(value.toString()); } catch (IllegalArgumentException ignored) { return null; }
    }

    private String indexName(UUID siteId) { return "powercms-admin-" + environment + "-" + siteId.toString().replace("-", ""); }
    private String publicIndexName(UUID siteId) { return "powercms-public-" + environment + "-" + siteId.toString().replace("-", ""); }

    /**
     * 管理索引别名（对外暴露，供控制器 / MCP 工具统一取用）。
     *
     * <p>此前 {@code HybridSearchController} 把 {@code powercms-admin-default-*} 硬编码在方法体里：
     * 环境名来自 {@code spring.profiles.active}，非 {@code default} 的部署会静默检索**不存在的索引**
     * （返回 0 条而非报错）。统一从此处取值即可消除该漂移。
     */
    public String adminIndex(UUID siteId) { return indexName(siteId); }

    /** 访客索引别名（对外暴露，理由同 {@link #adminIndex(UUID)}）。 */
    public String publicIndexAlias(UUID siteId) { return publicIndexName(siteId); }

    /**
     * 搜索建议（FR-SEARCH-004「搜索建议」）。
     *
     * @param indexOrAlias 索引别名（{@link #adminIndex}/{@link #publicIndexAlias}）
     * @param fieldKey     索引键（由 {@code SearchQueryResolver} 校验为 keyword 语义的键）
     */
    public List<String> suggest(String indexOrAlias, String fieldKey, String prefix, int limit) {
        if (indexOrAlias == null || fieldKey == null) return List.of();
        try {
            return store.suggest(indexOrAlias, fieldKey, prefix, limit);
        } catch (RuntimeException ex) {
            // 建议是辅助能力：后端不支持/索引缺失时返回空列表，不得让前端弹错
            log.warn("搜索建议失败 index={} field={}：{}", indexOrAlias, fieldKey, ex.getMessage());
            return List.of();
        }
    }

    /** 从索引名反推 siteId（用于 {@link #rebuild} 时按当前映射写入）。 */
    private UUID siteIdFromIndex(String index) {
        // powercms-public-<env>-<siteIdNoDash> 或 powercms-admin-<env>-<siteIdNoDash>
        int lastDash = index.lastIndexOf('-');
        if (lastDash < 0) throw new IllegalArgumentException("无法从索引名解析 siteId: " + index);
        String suffix = index.substring(lastDash + 1);
        if (suffix.length() != 32) throw new IllegalArgumentException("索引名 siteId 段长度异常: " + index);
        return UUID.fromString(suffix.substring(0, 8) + "-" + suffix.substring(8, 12) + "-"
            + suffix.substring(12, 16) + "-" + suffix.substring(16, 20) + "-" + suffix.substring(20));
    }
    private String candidateName(UUID siteId) { return indexName(siteId) + "-candidate-" + UUID.randomUUID().toString().replace("-", ""); }
}
