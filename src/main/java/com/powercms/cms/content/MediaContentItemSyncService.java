package com.powercms.cms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.media.MediaAssetDeletedEvent;
import com.powercms.cms.media.MediaAssetReadyEvent;
import com.powercms.cms.media.MediaAssetRegisteredEvent;
import com.powercms.cms.media.MediaContentItemBridge;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.search.AdminSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 媒体资产 ↔ MEDIA 内容项同步（FR-CM-MEDIA-010/011）：媒体资产应同时作为 MEDIA 内容类型
 * 的内容实例存在，使媒体在内容检索（content_item）与全局搜索（search index）中可发现。
 *
 * <ul>
 *   <li>上传建账 → {@link MediaAssetRegisteredEvent}：创建 MEDIA 内容项（v1）并回填
 *       media_asset.content_item_id；</li>
 *   <li>对账就绪 → {@link MediaAssetReadyEvent}：追加内容版本，落处理元数据字段并重索引；</li>
 *   <li>资产删除 → {@link MediaAssetDeletedEvent}：联动删除对应内容项与版本；</li>
 *   <li>应用启动 → {@link ApplicationReadyEvent}：为存量已归类资产补齐内容项。</li>
 * </ul>
 *
 * 幂等：内容项已存在时仅更新，不重复创建。
 */
@Service
public class MediaContentItemSyncService {

    public static final Logger log = LoggerFactory.getLogger(MediaContentItemSyncService.class);

    /**
     * 内容文档配额的独立闸门（矩阵 G1/G2）：媒资同步会绕过 {@code ContentService#create}
     * 直接新增内容条目，这里是第二道控制点。可选注入：单测直接构造时为 null → 跳过。
     */
    @Autowired(required = false)
    private ContentDocumentQuotaGuard documentQuotaGuard;

    static final String SYNC_USER = "media-sync";
    static final String MEDIA_OBJECT_TYPE = "MEDIA";

    private final MediaContentItemBridge bridge;
    private final ContentItemRepository itemRepo;
    private final ContentVersionRepository versionRepo;
    private final ContentTypeRepository typeRepo;
    private final FieldDefinitionRepository fieldRepo;
    private final AdminSearchService search;
    private final ObjectMapper om;
    private final com.powercms.cms.model.repo.ContentTypeLifecycleRepository lifecycleRepo;
    private final com.powercms.cms.model.sandbox.ScriptSandboxService sandbox;

    public MediaContentItemSyncService(MediaContentItemBridge bridge,
                                       ContentItemRepository itemRepo,
                                       ContentVersionRepository versionRepo,
                                       ContentTypeRepository typeRepo,
                                       FieldDefinitionRepository fieldRepo,
                                       AdminSearchService search,
                                       ObjectMapper om,
                                       com.powercms.cms.model.repo.ContentTypeLifecycleRepository lifecycleRepo,
                                       com.powercms.cms.model.sandbox.ScriptSandboxService sandbox) {
        this.bridge = bridge;
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.typeRepo = typeRepo;
        this.fieldRepo = fieldRepo;
        this.search = search;
        this.om = om;
        this.lifecycleRepo = lifecycleRepo;
        this.sandbox = sandbox;
    }

    // ---- 事件入口 ----

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRegistered(MediaAssetRegisteredEvent event) {
        log.debug("media registered, sync content item asset={}", event.assetId());
        doSync(event.assetId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReady(MediaAssetReadyEvent event) {
        log.debug("media ready, refresh content item asset={} event={}", event.assetId(), event.event());
        doSync(event.assetId(), event.event());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeleted(MediaAssetDeletedEvent event) {
        doDeleteLinked(event.assetId(), event.contentItemId());
    }

    /** 存量补齐（FR-CM-MEDIA-011）：为历史已归类但无内容项的资产同步创建。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        var missing = bridge.findAllClassifiedWithoutContentItem();
        if (!missing.isEmpty()) {
            log.info("media content item backfill: {} classified assets missing content item", missing.size());
            missing.forEach(d -> {
                try {
                    doSync(d.id());
                } catch (Exception ex) {
                    log.warn("media content item backfill failed asset={} err={}", d.id(), ex.getMessage());
                }
            });
        }
        rebindDeprecatedTypeItems();
    }

    /**
     * 存量重绑（FR-CM-CT-014 兜底）：早期 MEDIA 内容项可能绑在已废弃的版本行上
     * （发布迁移先于资产同步完成，新内容项随后仍以旧类型创建）。启动时统一把
     * content_item.content_type_id 指向同 code 生效版本，保证内容搜索按模型筛选可见。
     */
    private void rebindDeprecatedTypeItems() {
        int fixed = 0;
        for (ContentItemEntity item : itemRepo.findByContentTypeIdIn(
                typeRepo.findDeprecatedMediaTypeIds().stream().toList())) {
            ContentTypeEntity deprecated = typeRepo.findById(item.getContentTypeId()).orElse(null);
            ContentTypeEntity active = deprecated == null ? null : resolveActiveVersion(deprecated);
            if (active == null) continue;
            item.setContentTypeId(active.getId());
            itemRepo.save(item);
            fixed++;
            index(item.getId());
            log.info("media content item type rebind contentItem={} {} -> {}",
                item.getId(), deprecated.getId(), active.getId());
        }
        if (fixed > 0) {
            log.info("media content item rebind done: {} items rebound to active type", fixed);
        }
    }

    // ---- internals ----

    /** 同步（创建或更新）某资产的 MEDIA 内容项；未归类或类型非 MEDIA 时跳过（无 webhook 事件上下文）。 */
    private void doSync(UUID assetId) {
        doSync(assetId, null);
    }

    /** 同步（创建或更新）某资产的 MEDIA 内容项；event 为触发本次同步的 Knox webhook 事件（可空）。 */
    private void doSync(UUID assetId, String event) {
        var detail = bridge.findById(assetId).orElse(null);
        if (detail == null || detail.mediaTypeId() == null) {
            return;
        }
        ContentTypeEntity ct = typeRepo.findById(detail.mediaTypeId()).orElse(null);
        if (ct == null || !MEDIA_OBJECT_TYPE.equals(ct.getObjectType())) {
            return;
        }
        ct = resolveActiveVersion(ct);
        if (ct == null) {
            return;
        }
        if (detail.contentItemId() != null) {
            updateContent(detail, ct, event);
        } else {
            createContent(detail, ct, event);
        }
    }

    /**
     * 归类可能指向已废弃的旧版本行（新版本发布前上传的存量资产）：同步时统一重定向到
     * 同 code 的最高非废弃版本，保证 MEDIA 内容项始终挂在生效模型上（FR-CM-CT-014）。
     */
    private ContentTypeEntity resolveActiveVersion(ContentTypeEntity ct) {
        if (!ContentTypeEntity.STATUS_DEPRECATED.equals(ct.getStatus())) {
            return ct;
        }
        ContentTypeEntity latest = null;
        for (ContentTypeEntity v : typeRepo.findBySiteIdAndCodeOrderByVersionAsc(ct.getSiteId(), ct.getCode())) {
            if (!ContentTypeEntity.STATUS_DEPRECATED.equals(v.getStatus())) {
                latest = v;
            }
        }
        if (latest == null) {
            log.warn("media content item sync skipped, no active version code={} deprecatedType={}",
                ct.getCode(), ct.getId());
            return null;
        }
        log.info("media content item type rebind: {} -> {} ({} v{})",
            ct.getId(), latest.getId(), latest.getCode(), latest.getVersion());
        return latest;
    }

    private void createContent(MediaContentItemBridge.AssetDetail d, ContentTypeEntity ct, String event) {
        // G1/G2 检索侧独立校验：写库之前断言，超限时商业版 409、社区版按三层口径只告警
        if (documentQuotaGuard != null) documentQuotaGuard.assertCanAdd(1);
        ContentItemEntity item = new ContentItemEntity();
        item.setSiteId(d.siteId());
        item.setContentTypeId(d.mediaTypeId());
        item.setTitle(titleOf(d));
        item.setStatus(ContentItemEntity.STATUS_DRAFT);
        item.setCreatedBy(SYNC_USER);
        item = itemRepo.save(item);
        versionRepo.save(ContentVersionEntity.create(item.getId(), 1, buildFieldValues(d, d.mediaTypeId(), event), ct.getVersion(),
            SYNC_USER, "媒体资产自动同步"));
        item.setCurrentVersion(1);
        itemRepo.save(item);
        bridge.linkContentItem(d.id(), item.getId());
        index(item.getId());
        log.info("media content item created asset={} contentItem={} type={}",
            d.id(), item.getId(), ct.getCode());
    }

    private void updateContent(MediaContentItemBridge.AssetDetail d, ContentTypeEntity ct, String event) {
        ContentItemEntity item = d.contentItemId() == null ? null
            : itemRepo.findById(d.contentItemId()).orElse(null);
        if (item == null) {
            // 关联悬空（内容项被外部删除）：重建
            bridge.linkContentItem(d.id(), null);
            createContent(d, ct, event);
            return;
        }
        int next = item.getCurrentVersion() + 1;
        versionRepo.save(ContentVersionEntity.create(item.getId(), next, buildFieldValues(d, d.mediaTypeId(), event), ct.getVersion(),
            SYNC_USER, "媒体状态更新"));
        item.setCurrentVersion(next);
        item.setTitle(titleOf(d));
        itemRepo.save(item);
        index(item.getId());
        log.info("media content item updated asset={} contentItem={} version={}",
            d.id(), item.getId(), next);
    }

    private void doDeleteLinked(UUID assetId, UUID contentItemId) {
        if (contentItemId == null) return;
        versionRepo.findByContentItemIdOrderByVersionDesc(contentItemId).forEach(versionRepo::delete);
        itemRepo.deleteById(contentItemId);
        log.info("media content item deleted asset={} contentItem={}", assetId, contentItemId);
    }

    private void index(UUID contentItemId) {
        try {
            search.indexItem(contentItemId);
        } catch (Exception ex) {
            log.warn("media content item search index failed contentItem={} err={}",
                contentItemId, ex.getMessage());
        }
    }

    /** 系统字段值（字段 code 与 MEDIA 内容类型 field_definition 一致，FR-CM-MEDIA-003）。event 为触发 webhook 事件（可空）。 */
    private String buildFieldValues(MediaContentItemBridge.AssetDetail d, UUID contentTypeId, String event) {
        ObjectNode root = om.createObjectNode();
        root.put("title_s", titleOf(d));
        root.put("fileName_s", d.filename());
        if (d.mimeType() != null && !d.mimeType().isBlank()) root.put("mimeType_s", d.mimeType());
        root.put("fileSize_i", d.fileSize());
        if (d.storageKey() != null && !d.storageKey().isBlank()) root.put("storageKey_s", d.storageKey());
        if (d.contentHash() != null && !d.contentHash().isBlank()) root.put("hash_s", d.contentHash());
        if (d.kind() != null && !d.kind().isBlank()) root.put("kind_s", d.kind());
        root.put("status_s", d.status() == null ? "PROCESSING" : d.status());
        applyMetadata(root, d);
        // FR-AI-MEDIA-001：转写稿回写 MEDIA 内容项（可编辑、参与媒体语义检索）
        if (d.transcript() != null && !d.transcript().isBlank()) {
            root.put("transcript_t", d.transcript());
            if (d.transcriptLang() != null && !d.transcriptLang().isBlank()) {
                root.put("transcriptLang_s", d.transcriptLang());
            }
            // GOV-007 产物 AI 标识：模型标识随转写稿一并落字段（内容项级 GOV-001 由 markAiGenerated 承担）
            if (d.transcriptModel() != null && !d.transcriptModel().isBlank()) {
                root.put("transcriptModel_s", d.transcriptModel());
            }
        }
        applyWebhookScript(root, d, contentTypeId, event);
        return root.toString();
    }

    /**
     * WEBHOOK 生命周期脚本（FR-CM-MEDIA-012 扩展）：外部系统（如 Knox）经 webhook 回调后，
     * 允许按内容模型配置的动态脚本对本次落库的字段键值做最终修改。
     * 脚本入参：
     * <ul>
     *   <li>fields=当前字段 Map；</li>
     *   <li>media=回调媒体数据 Map（width/height/bitrate/durationMs/format/posterPath 等，来自
     *       media_asset.metadata 合并结果，即 Knox webhook 提交的数据）；</li>
     *   <li>asset={id,kind,status,filename}；event=实际 Knox webhook 事件名（如 metadata.obtained /
     *       poster.obtained / media.added；对账/补查触发时为空串）。</li>
     * </ul>
     * 返回 Map 时按返回键值覆盖本模型已定义字段（含既有字段与字段定义中尚未有值的字段），
     * 未定义字段被丢弃，避免脚本注入未知字段；数值/布尔补丁保留原生类型，不转字符串。
     *
     * <p>监听事件筛选：钩子 listenEvents（逗号分隔）非空时，仅当 event 命中白名单才执行；
     * 为空表示监听全部 Knox webhook 事件（由脚本内按 event 自行分支）。
     */
    private void applyWebhookScript(ObjectNode root, MediaContentItemBridge.AssetDetail d, UUID contentTypeId, String event) {
        if (contentTypeId == null) return;
        var hook = lifecycleRepo.findByContentTypeIdAndEvent(contentTypeId, "WEBHOOK").orElse(null);
        if (hook == null || !hook.isEnabled() || hook.getScriptContent() == null
            || hook.getScriptContent().isBlank()) {
            return;
        }
        if (!listens(hook.getListenEvents(), event)) {
            log.debug("media webhook script skipped by listen filter asset={} event={} listen={}",
                d.id(), event, hook.getListenEvents());
            return;
        }
        try {
            Map<String, Object> fields = om.convertValue(root, Map.class);
            Map<String, Object> asset = Map.of(
                "id", d.id().toString(),
                "kind", d.kind() == null ? "" : d.kind(),
                "status", d.status() == null ? "" : d.status(),
                "filename", d.filename() == null ? "" : d.filename());
            Object result = sandbox.evaluate(hook.getScriptContent(), hook.getScriptLang(),
                Map.of("fields", fields, "event", event == null ? "" : event, "asset", asset, "media", callbackMedia(d)));
            if (result instanceof Map<?, ?> patch) {
                Set<String> defined = definedFieldCodes(contentTypeId);
                int applied = 0;
                for (Map.Entry<?, ?> e : patch.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    String key = String.valueOf(e.getKey());
                    if (!root.has(key) && !defined.contains(key)) continue; // 仅允许写入已定义字段
                    putPatchValue(root, key, e.getValue());
                    applied++;
                }
                log.debug("media webhook script applied asset={} patched={}", d.id(), applied);
            }
        } catch (Exception ex) {
            // 脚本失败不阻断媒体同步：保留系统字段原值
            log.warn("media webhook script failed asset={} err={}", d.id(), ex.getMessage());
        }
    }

    /** 监听事件筛选：listenEvents 为空（监听全部）时恒真；否则 event 必须命中逗号分隔白名单。 */
    private boolean listens(String listenEvents, String event) {
        if (listenEvents == null || listenEvents.isBlank()) return true;
        if (event == null || event.isBlank()) return false; // 无 webhook 事件上下文（对账/补查）不执行
        for (String allowed : listenEvents.split(",")) {
            if (event.equals(allowed.trim())) return true;
        }
        return false;
    }

    /** webhook 回调提交的媒体数据（media_asset.metadata 合并结果）解析为 Map，供钩子脚本按需写入字段。 */
    private Map<String, Object> callbackMedia(MediaContentItemBridge.AssetDetail d) {
        if (d.metadata() == null || d.metadata().isBlank()) return Map.of();
        try {
            return om.convertValue(om.readTree(d.metadata()), Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** 内容模型已定义字段 code 集合（系统字段 + 自定义字段），作为钩子补丁的写入白名单。 */
    private Set<String> definedFieldCodes(UUID contentTypeId) {
        Set<String> codes = new HashSet<>();
        for (FieldDefinitionEntity f : fieldRepo.findByContentTypeIdOrderBySortOrder(contentTypeId)) {
            codes.add(f.getCode());
        }
        return codes;
    }

    /** 钩子补丁值落字段：数值/布尔保留原生类型，其余按字符串写入（数值字段不得字符串化，否则索引排序失效）。 */
    private void putPatchValue(ObjectNode root, String key, Object value) {
        if (value instanceof Integer i) root.put(key, i);
        else if (value instanceof Long l) root.put(key, l);
        else if (value instanceof Double d) root.put(key, d);
        else if (value instanceof Float f) root.put(key, f.doubleValue());
        else if (value instanceof java.math.BigDecimal bd) root.put(key, bd);
        else if (value instanceof Boolean b) root.put(key, b);
        else root.put(key, String.valueOf(value));
    }

    /** 处理产物元数据（FR-KNX-024）映射为 MEDIA 类型专属字段。 */
    private void applyMetadata(ObjectNode root, MediaContentItemBridge.AssetDetail d) {
        if (d.metadata() == null || d.metadata().isBlank()) return;
        try {
            JsonNode m = om.readTree(d.metadata());
            if (m.hasNonNull("format")) root.put("format_s", m.get("format").asText());
            putNumberIfPositive(root, "width_i", m, "width");
            putNumberIfPositive(root, "height_i", m, "height");
            putNumberIfPositive(root, "durationMs_i", m, "durationMs");
            putNumberIfPositive(root, "bitrate_d", m, "bitrate");
            if (m.hasNonNull("posterPath")) {
                String poster = m.get("posterPath").asText();
                // 预览图统一字段：视频/音频落 thumbnailUrl_s，video 保留 posterUrl_s、audio 保留 coverUrl_s 兼容
                root.put("thumbnailUrl_s", poster);
                String code = "audio".equalsIgnoreCase(d.kind()) ? "coverUrl_s" : "posterUrl_s";
                root.put(code, poster);
            }
            if (m.hasNonNull("hlsMasterUrl")) root.put("hlsMasterUrl_s", m.get("hlsMasterUrl").asText());
        } catch (Exception ignored) {
            // metadata 非结构化时忽略，仅保留基础系统字段
        }
    }

    /** 正数数值字段写入（Knox 用 0 表示"无"，0 不入索引避免 0 值显示）。 */
    private void putNumberIfPositive(ObjectNode root, String fieldCode, JsonNode meta, String metaKey) {
        if (meta.hasNonNull(metaKey) && meta.get(metaKey).isNumber()) {
            long v = meta.get(metaKey).asLong();
            if (v > 0) root.put(fieldCode, v);
        }
    }

    private static String titleOf(MediaContentItemBridge.AssetDetail d) {
        return d.title() != null && !d.title().isBlank() ? d.title() : d.filename();
    }
}
