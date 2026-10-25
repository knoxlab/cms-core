package com.powercms.cms.content;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内容物理删除（内容搜索页「已归档内容可删除」）。
 *
 * <p>语义与 {@code SiteDeletionService}（FR-SITE-LC-007 站点删除）同构、但作用域收窄到单条内容：
 * <ol>
 *   <li><b>前置条件</b>：仅 {@code ARCHIVED} 内容可删——未归档内容必须先归档，
 *       与站点删除「先归档再删除」的口径一致；</li>
 *   <li><b>级联清除</b>：按「引用 content_item 的子表」目录逐表删除（版本、工作流、发布快照、
 *       关系、KG 投影、统计等），再删 content_item 行——目录口径来自站点删除的
 *       {@code SitePurgeCatalog.EDGES} 与 db/migration 全量列扫描的并集；</li>
 *   <li><b>检索索引</b>：同步删除管理索引与访客索引中的文档（否则访客搜索仍能命中已删内容）；</li>
 *   <li><b>审计保留</b>：删除动作本身写审计行（FR-AUDIT-001，audit_event 不随内容删除）。</li>
 * </ol>
 *
 * <p><b>不删除的引用</b>（悬空但无外键、保留比删除更安全）：聊天室/广告位对内容的绑定
 * （用户互动数据不应随内容销毁）；{@code media_asset.content_item_id} 与
 * {@code sys_user.profile_content_id} 则置空解除绑定——媒体资产与用户是独立生命周期对象。
 */
@Service
@Transactional
public class ContentDeletionService {

    private static final Logger log = LoggerFactory.getLogger(ContentDeletionService.class);

    /** 单次批量操作条数上限（矩阵 B7：社区版 ≤500 条/批）。 */
    static final int MAX_BATCH_SIZE = 500;

    private final ContentItemRepository itemRepo;
    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    /** 依赖倒置防循环：search 模块依赖 content（索引文档构建），直接注入可能构造期成环。 */
    private final ObjectProvider<com.powercms.cms.search.AdminSearchService> searchProvider;

    ContentDeletionService(ContentItemRepository itemRepo, JdbcTemplate jdbc, AuditService auditService,
                           ObjectProvider<com.powercms.cms.search.AdminSearchService> searchProvider) {
        this.itemRepo = itemRepo;
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.searchProvider = searchProvider;
    }

    /**
     * 物理删除单条已归档内容及其全部关联数据。
     *
     * @return 删除影响面（表 → 行数），用于审计 detail
     * @throws BusinessException NOT_FOUND（内容不存在）或 CONFLICT（未归档）
     */
    public Map<String, Long> deleteItem(UUID id) {
        ContentItemEntity item = itemRepo.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        if (!ContentItemEntity.STATUS_ARCHIVED.equals(item.getStatus())) {
            throw BusinessException.keyed(ErrorCodes.CONFLICT, "内容未归档，仅已归档内容可删除",
                "error.content.delete.not.archived");
        }
        Map<String, Long> impact = purgeRelated(id);
        itemRepo.deleteById(id);
        deleteSearchDocs(item.getSiteId(), id);

        String detail = "内容已物理删除（关联数据级联清除，审计保留）："
            + impact.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("无关联数据")
            + "；title=" + item.getTitle();
        auditService.write(AuditService.currentActor(), "CONTENT_DELETE", "content_item",
            id.toString(), item.getSiteId(), "SUCCESS", detail);
        log.info("内容已删除 id={} siteId={} impact={}", id, item.getSiteId(), impact);
        return impact;
    }

    /**
     * 批量删除：逐条独立成败——已归档的删除、未归档的进 failed（附原因），不整批回滚。
     */
    public ContentService.BatchResult deleteBatch(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "未选择任何内容");
        }
        // 批量条数按社区建议基线（规格 §4.1：单次 ≤200 条），超出提示缩小批次
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "单次最多删除 " + MAX_BATCH_SIZE + " 条内容");
        }
        List<String> succeeded = new ArrayList<>();
        List<ContentService.BatchFailure> failed = new ArrayList<>();
        for (UUID id : ids) {
            try {
                deleteItem(id);
                succeeded.add(id.toString());
            } catch (RuntimeException ex) {
                failed.add(new ContentService.BatchFailure(id.toString(), ex.getMessage()));
            }
        }
        return new ContentService.BatchResult(succeeded, failed);
    }

    /** 级联清除的表目录（顺序即删除顺序：先子后父，最后 content_item 本身）。 */
    private Map<String, Long> purgeRelated(UUID id) {
        Map<String, Long> impact = new LinkedHashMap<>();
        // KG 投影：kg_node.id = content_item.id；边先删（引用节点）
        purge(impact, "kg_edge", "source_node = ? OR target_node = ?", id, id);
        purge(impact, "kg_node", "id = ?", id);
        // 工作流：确认 → 任务 → 流转日志
        purge(impact, "workflow_task_confirmation",
            "task_id IN (SELECT id FROM workflow_task WHERE content_item_id = ?)", id);
        purge(impact, "workflow_task", "content_item_id = ?", id);
        purge(impact, "workflow_transition_log", "content_item_id = ?", id);
        // 版本与内容关系
        purge(impact, "content_version", "content_item_id = ?", id);
        purge(impact, "content_relation_item", "source_content_id = ? OR target_content_id = ?", id, id);
        purge(impact, "relation_template_link_log",
            "record_content_id = ? OR anchor_content_id = ?", id, id);
        purge(impact, "content_source_fact", "content_item_id = ?", id);
        // 页面视觉布局与发布快照
        purge(impact, "page_visual_composition", "page_id = ?", id);
        purge(impact, "release_content", "content_item_id = ?", id);
        purge(impact, "release_page", "page_id = ?", id);
        // 表单提交审计、AI 建议/批处理/黑名单
        purge(impact, "form_submission_audit", "content_item_id = ?", id);
        purge(impact, "ai_content_suggestion", "content_item_id = ?", id);
        purge(impact, "ai_batch_job_item", "content_item_id = ?", id);
        purge(impact, "ai_ask_blacklist", "content_id = ?", id);
        // 统计（无外键，悬空行会让统计页出现「幽灵内容」）
        purge(impact, "content_citation_count", "content_id = ?", id);
        purge(impact, "analytics_content_daily", "content_id = ?", id);
        // 解除独立生命周期对象的绑定（不删除资产/用户本身）
        jdbc.update("UPDATE media_asset SET content_item_id = NULL WHERE content_item_id = ?", id);
        jdbc.update("UPDATE sys_user SET profile_content_id = NULL WHERE profile_content_id = ?", id);
        return impact;
    }

    /** 计数 + 按条件删除；表名/条件全部是编译期常量（无运行时拼接风险）。 */
    private void purge(Map<String, Long> impact, String table, String where, Object... args) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class, args);
        if (n != null && n > 0) jdbc.update("DELETE FROM " + table + " WHERE " + where, args);
        impact.put(table, n != null ? n : 0L);
    }

    /** 删除管理索引与访客索引中的文档；索引侧失败不影响删除本身（下次重建会兜底）。 */
    private void deleteSearchDocs(UUID siteId, UUID id) {
        com.powercms.cms.search.AdminSearchService search = searchProvider.getIfAvailable();
        if (search == null) return;
        try {
            search.deleteAdminDocument(siteId, id);
            search.deletePublicDocument(siteId, id);
        } catch (RuntimeException ex) {
            log.warn("删除内容搜索索引文档失败 content={}：{}", id, ex.getMessage());
        }
    }
}
