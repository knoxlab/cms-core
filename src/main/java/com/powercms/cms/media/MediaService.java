package com.powercms.cms.media;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.entity.MediaVersionEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaVersionRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class MediaService {

    private final MediaAssetRepository assetRepo;
    private final MediaVersionRepository versionRepo;
    private final ContentTypeRepository typeRepo;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    MediaService(MediaAssetRepository assetRepo, MediaVersionRepository versionRepo,
                 ContentTypeRepository typeRepo, ApplicationEventPublisher events,
                 AuditService audit, ObjectMapper objectMapper) {
        this.assetRepo = assetRepo;
        this.versionRepo = versionRepo;
        this.typeRepo = typeRepo;
        this.events = events;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** 媒体资产信息（FR-KNX-033）：含媒体内容类型徽标与归类/元数据，驱动媒体库过滤与展示。 */
    public record AssetInfo(UUID id, UUID siteId, String title, String filename, String mimeType,
                            long fileSize, String contentHash, int currentVersion,
                            String status, String kind, String mediaTypeCode, String mediaTypeName,
                            String folderPath, String metadata) {
        public static AssetInfo from(MediaAssetEntity a, Map<UUID, ContentTypeEntity> typeById) {
            ContentTypeEntity t = a.getMediaTypeId() == null ? null : typeById.get(a.getMediaTypeId());
            return new AssetInfo(a.getId(), a.getSiteId(), a.getTitle(), a.getFilename(),
                a.getMimeType(), a.getFileSize(), a.getContentHash(), a.getCurrentVersion(),
                a.getStatus(), a.getKind(), t == null ? null : t.getCode(),
                t == null ? null : t.getName(), a.getFolderPath(), a.getMetadata());
        }
    }

    public AssetInfo register(UUID siteId, String filename, String contentHash, String mimeType,
                                long fileSize, String storageKey) {
        return register(siteId, UUID.randomUUID(), filename, contentHash, mimeType, fileSize, storageKey);
    }

    /** 预分配资产 ID 的注册重载：{@code storagePathPattern} 的 {@code {objectId}} 宏需与最终资产 ID 一致（FR-CM-MEDIA-051）。 */
    public AssetInfo register(UUID siteId, UUID assetId, String filename, String contentHash,
                              String mimeType, long fileSize, String storageKey) {
        var existing = assetRepo.findBySiteIdAndContentHash(siteId, contentHash);
        observeMediaBaseline(fileSize, existing.isEmpty());
        // 去重检查（FR-ASSET-002）：相同 contentHash 创建独立逻辑记录但指向同一二进制
        if (existing.isPresent()) {
            MediaAssetEntity asset = existing.get();
            int nextVer = asset.getCurrentVersion() + 1;
            versionRepo.save(MediaVersionEntity.create(asset.getId(), nextVer, contentHash,
                filename, mimeType, fileSize, storageKey));
            asset.setCurrentVersion(nextVer);
            asset.setFilename(filename);
            assetRepo.save(asset);
            return AssetInfo.from(asset, types(asset.getId()));
        }

        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setId(assetId);
        asset.setSiteId(siteId);
        asset.setFilename(filename);
        asset.setContentHash(contentHash);
        asset.setMimeType(mimeType);
        asset.setFileSize(fileSize);
        asset = assetRepo.save(asset);

        versionRepo.save(MediaVersionEntity.create(asset.getId(), 1, contentHash,
            filename, mimeType, fileSize, storageKey));
        asset.setCurrentVersion(1);
        assetRepo.save(asset);
        return AssetInfo.from(asset, types(asset.getId()));
    }

    /**
     * FR-KNX-051 按资产替换上传：为指定资产追加新版本并更新账本字段。
     * 与 {@link #register(UUID, UUID, String, String, String, long, String)} 的按哈希去重不同——
     * 替换严格绑定到目标资产（即使新哈希与库内其它资产相同也只追加到该资产，避免误绑）。
     * 归类/内容类型保持不变；storageKey 先写占位，上传成功由上传服务回填真实 Knox 地址。
     *
     * @return 新版本号
     */
    public int replaceVersion(UUID siteId, UUID assetId, String filename, String contentHash,
                              String mimeType, long fileSize, String storageKey) {
        MediaAssetEntity asset = assetRepo.findById(assetId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
        if (!siteId.equals(asset.getSiteId())) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在");
        }
        int nextVer = asset.getCurrentVersion() + 1;
        versionRepo.save(MediaVersionEntity.create(assetId, nextVer, contentHash,
            filename, mimeType, fileSize, storageKey));
        asset.setCurrentVersion(nextVer);
        asset.setFilename(filename);
        asset.setContentHash(contentHash);
        asset.setMimeType(mimeType);
        asset.setFileSize(fileSize);
        assetRepo.save(asset);
        return nextVer;
    }

    /** 默认关键词覆盖面（文件名/标题/元数据），保持既有检索口径不变。 */
    public static final Set<String> DEFAULT_SEARCH_FIELDS = Set.of("filename", "title", "metadata");

    /**
     * FR-AGT-MEDIA-001 可显式打开的 AI 产物检索面：转写稿 / 摘要 / Alt 文本。
     * 默认不参与匹配（避免大字段文本偶然命中干扰结构化检索），需调用方显式声明。
     */
    public static final Set<String> AI_SEARCH_FIELDS = Set.of("alt_text", "summary", "transcript");

    /** 全部可检索字段面 = 默认面 ∪ AI 产物面。 */
    public static final Set<String> SEARCH_FIELDS = Set.of(
        "filename", "title", "metadata", "alt_text", "summary", "transcript");

    /** 检索命中：资产摘要 + 命中的关键词面（供工具透出，便于 Agent 解释命中原因）。 */
    public record SearchHit(AssetInfo asset, List<String> matchedIn) {}

    /** 媒体库检索（FR-KNX-033）：kind/媒体类型/状态过滤 + 文件名与元数据关键字搜索。 */
    public List<AssetInfo> list(UUID siteId, String kind, UUID mediaTypeId, String q, String status) {
        return list(siteId, kind, mediaTypeId, q, status, null);
    }

    /** 同 {@link #list}，并按目录筛选：{@code /} 只含根目录，其它路径含该目录及其子目录。 */
    public List<AssetInfo> list(UUID siteId, String kind, UUID mediaTypeId, String q, String status, String folderPath) {
        return search(siteId, kind, mediaTypeId, status, q, DEFAULT_SEARCH_FIELDS, folderPath).stream()
            .map(SearchHit::asset).toList();
    }

    /**
     * 媒体库分页检索（FR-KNX-033）：在 {@link #search} 的过滤结果上做服务端排序 + 切页。
     *
     * <p>排序必须与分页同侧：只对已加载窗口排序会得到「页内有序、页间乱序」的错位列表。
     * 排序语义见 {@link MediaSort}（前端中文自然排序的 Java 等价实现）。
     *
     * <p><b>已知边界</b>：过滤与排序仍在内存中完成——本方法复用 {@link #search} 的既有口径
     * （该路径本就整表读入后在 Java 侧过滤），因此本方法减少的是**传输与渲染**的数据量
     * （浏览器只拿到当前页），而非数据库读取量。数据量进一步增长时需将 kind/status/媒体类型
     * 过滤下沉到 SQL，届时关键词面仍需内存匹配，属独立优化。
     */
    public MediaSort.Slice<SearchHit> page(UUID siteId, String kind, UUID mediaTypeId, String q,
                                           String status, String sort, int page, int size) {
        return page(siteId, kind, mediaTypeId, q, status, sort, page, size, null);
    }

    /** 同 {@link #page(UUID, String, UUID, String, String, String, int, int)}，并按目录筛选。 */
    public MediaSort.Slice<SearchHit> page(UUID siteId, String kind, UUID mediaTypeId, String q,
                                           String status, String sort, int page, int size, String folderPath) {
        List<SearchHit> hits = new ArrayList<>(search(siteId, kind, mediaTypeId, status, q, DEFAULT_SEARCH_FIELDS, folderPath));
        hits.sort(Comparator.comparing(SearchHit::asset, MediaSort.comparator(sort)));
        return MediaSort.slice(hits, page, size);
    }

    /**
     * 媒体库检索（FR-AGT-MEDIA-001）：结构化过滤 + 指定字段面的关键词匹配。
     *
     * @param fields 参与匹配的字段面，{@code null}/空 = {@link #DEFAULT_SEARCH_FIELDS}；
     *               AI 产物面（{@link #AI_SEARCH_FIELDS}）须调用方显式声明。
     * @return 命中的资产与其命中面；关键词为空时返回全部（结构化过滤后）且命中面为空列表。
     */
    public List<SearchHit> search(UUID siteId, String kind, UUID mediaTypeId, String status,
                                  String q, Set<String> fields) {
        return search(siteId, kind, mediaTypeId, status, q, fields, null);
    }

    public List<SearchHit> search(UUID siteId, String kind, UUID mediaTypeId, String status,
                                  String q, Set<String> fields, String folderPath) {
        List<MediaAssetEntity> assets = assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId);
        if (kind != null && !kind.isBlank()) {
            assets = assets.stream().filter(a -> kind.equalsIgnoreCase(a.getKind())).toList();
        }
        if (mediaTypeId != null) {
            assets = assets.stream().filter(a -> mediaTypeId.equals(a.getMediaTypeId())).toList();
        }
        if (status != null && !status.isBlank()) {
            assets = assets.stream().filter(a -> status.equalsIgnoreCase(a.getStatus())).toList();
        }
        if (folderPath != null && !folderPath.isBlank()) {
            assets = assets.stream().filter(a -> matchesFolder(a.getFolderPath(), folderPath)).toList();
        }
        Set<String> scopes = fields == null || fields.isEmpty() ? DEFAULT_SEARCH_FIELDS : fields;
        String query = q == null ? null : q.trim().toLowerCase();
        if (query == null || query.isBlank()) {
            Map<UUID, ContentTypeEntity> allTypes = types(assets.stream()
                .map(MediaAssetEntity::getMediaTypeId).filter(Objects::nonNull).distinct().toList());
            return assets.stream()
                .map(a -> new SearchHit(AssetInfo.from(a, allTypes), List.of())).toList();
        }
        Map<UUID, ContentTypeEntity> typeById = types(assets.stream()
            .map(MediaAssetEntity::getMediaTypeId).filter(Objects::nonNull).distinct().toList());
        List<SearchHit> hits = new ArrayList<>();
        for (MediaAssetEntity asset : assets) {
            List<String> matched = new ArrayList<>(4);
            if (scopes.contains("filename") && containsQuery(asset.getFilename(), query)) matched.add("filename");
            if (scopes.contains("title") && containsQuery(asset.getTitle(), query)) matched.add("title");
            if (scopes.contains("metadata") && containsQuery(asset.getMetadata(), query)) matched.add("metadata");
            if (scopes.contains("alt_text") && containsQuery(asset.getAltText(), query)) matched.add("alt_text");
            if (scopes.contains("summary") && containsQuery(asset.getSummary(), query)) matched.add("summary");
            if (scopes.contains("transcript") && containsQuery(asset.getTranscript(), query)) matched.add("transcript");
            if (!matched.isEmpty()) {
                hits.add(new SearchHit(AssetInfo.from(asset, typeById), List.copyOf(matched)));
            }
        }
        return hits;
    }

    private static boolean containsQuery(String value, String lowerQuery) {
        return value != null && value.toLowerCase().contains(lowerQuery);
    }

    /**
     * 目录筛选：{@code /} 只匹配未挂目录或挂在根上的资产；其它路径匹配该目录及其子目录。
     */
    static boolean matchesFolder(String assetPath, String filter) {
        String want = filter.trim();
        while (want.endsWith("/") && want.length() > 1) {
            want = want.substring(0, want.length() - 1);
        }
        String path = assetPath == null ? "" : assetPath.trim();
        if ("/".equals(want)) {
            return path.isEmpty() || "/".equals(path);
        }
        if (!want.startsWith("/")) {
            want = "/" + want;
        }
        return path.equals(want) || path.startsWith(want + "/");
    }

    public List<AssetInfo> list(UUID siteId) {
        return toInfos(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId));
    }

    public AssetInfo get(UUID id) {
        MediaAssetEntity asset = require(id);
        return AssetInfo.from(asset, types(id));
    }

    public void delete(UUID id) {
        MediaAssetEntity asset = require(id);
        UUID contentItemId = asset.getContentItemId();
        versionRepo.findByMediaAssetIdOrderByVersionDesc(id).forEach(v -> versionRepo.delete(v));
        assetRepo.delete(asset);
        // 联动删除 MEDIA 内容项（FR-CM-MEDIA-011），事件携带关联 ID 供删除后消费
        events.publishEvent(new MediaAssetDeletedEvent(asset.getSiteId(), id, contentItemId));
    }

    // ── 批次 5 媒体写面（FR-AGT-MEDIA-003/005）────────────────────────────

    /** 元数据补丁：{@code null} = 不变更；空串 = 清空该字段（Agent 需要显式清除能力）。 */
    public record MetadataPatch(String title, String altText, String copyright, String tags, String folderPath) {}

    /** 元数据快照：供工具生成 dry-run diff 与并发控制。 */
    public record MediaMetadata(UUID assetId, UUID siteId, int currentVersion, String filename, String title,
                                String altText, String altTextModel, String copyright, String tags,
                                String folderPath) {
        /** AI 生成的 Alt 文本（有模型标识）——修订后必须清除标识，即「人工修订标记」。 */
        public boolean aiGeneratedAltText() {
            return altTextModel != null && !altTextModel.isBlank();
        }
    }

    /** 元数据写入结果：changedKeys 供审计与回执；aiAltTextRevised 标记「AI 产物被人工/Agent 修订」。 */
    public record MetadataResult(UUID assetId, int currentVersion, List<String> changedKeys,
                                 boolean aiAltTextRevised) {}

    public record MoveResult(UUID assetId, String title, String filename, String fromFolder, String toFolder) {}

    // ── 批次 6 媒体 AI 产物（FR-AGT-MEDIA-004）─────────────────────────────

    /**
     * 资产 AI 产物快照（FR-AI-MEDIA-001/002 + GOV-007）：转写稿 / 摘要 / Alt 文本及其模型标识与同步时间。
     *
     * <p>产物由专用处理器逐类生成（{@code generate_media_artifacts} 异步任务），本快照供任务前置校验
     * 与 MCP 读工具 {@code get_media_artifacts} 复用；模型标识为空表示无产物或已转人工修订。
     */
    public record ArtifactsView(UUID assetId, UUID siteId, String kind, String filename, String mimeType,
                                String altText, String altTextModel,
                                String summary, String summaryModel, Instant summarySyncedAt,
                                String transcript, String transcriptLang, String transcriptModel,
                                Instant transcriptSyncedAt,
                                String chapters, String chaptersModel, Instant chaptersSyncedAt) {

        public boolean hasAltText() { return altText != null && !altText.isBlank(); }

        public boolean hasSummary() { return summary != null && !summary.isBlank(); }

        public boolean hasTranscript() { return transcript != null && !transcript.isBlank(); }

        /** 图片资产判定：kind=image 或 MIME 以 image/ 开头（与 MediaAltTextService 口径一致）。 */
        public boolean image() {
            return "image".equalsIgnoreCase(kind)
                || (mimeType != null && mimeType.toLowerCase().startsWith("image/"));
        }
    }

    /** 读取资产 AI 产物快照；跨站点按 NOT_FOUND 处理（不泄漏他站资产存在性）。 */
    public ArtifactsView artifactsOf(UUID siteId, UUID assetId) {
        MediaAssetEntity asset = siteId == null ? require(assetId) : requireInSite(siteId, assetId);
        return new ArtifactsView(asset.getId(), asset.getSiteId(), asset.getKind(), asset.getFilename(),
            asset.getMimeType(), asset.getAltText(), asset.getAltTextModel(),
            asset.getSummary(), asset.getSummaryModel(), asset.getSummarySyncedAt(),
            asset.getTranscript(), asset.getTranscriptLang(), asset.getTranscriptModel(),
            asset.getTranscriptSyncedAt(),
            asset.getChapters(), asset.getChaptersModel(), asset.getChaptersSyncedAt());
    }

    /** 站点域内元数据快照（越权返回 NOT_FOUND，不泄露他站资产）。 */
    public MediaMetadata metadataOf(UUID siteId, UUID assetId) {
        MediaAssetEntity a = requireInSite(siteId, assetId);
        return new MediaMetadata(a.getId(), a.getSiteId(), a.getCurrentVersion(), a.getFilename(),
            a.getTitle(), a.getAltText(), a.getAltTextModel(), a.getCopyright(), a.getTags(),
            a.getFolderPath());
    }

    /**
     * 元数据编辑（FR-AGT-MEDIA-003）：字段级 diff + {@code expected_version} 乐观锁。
     *
     * <p>并发口径：{@code currentVersion} 是业务版本号（上传/替换递增），Agent 预览时取到的版本
     * 若在执行前被替换上传变更，则拒绝写入（CONFLICT），避免「预览是 A、执行覆盖了 B」。
     * Alt 文本被改写时清除 AI 模型标识（GOV-007 人工修订标记），并在审计中记录原模型。
     */
    public MetadataResult updateMetadata(UUID siteId, UUID assetId, MetadataPatch patch,
                                        Integer expectedVersion, String actor) {
        MediaAssetEntity asset = requireInSite(siteId, assetId);
        if (expectedVersion != null && expectedVersion != asset.getCurrentVersion()) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "媒体资产版本已变化（期望 " + expectedVersion + "，实际 " + asset.getCurrentVersion()
                    + "），请重新预览后再提交");
        }
        List<String> changed = new ArrayList<>();
        boolean aiAltTextRevised = false;
        if (patch.title() != null && !Objects.equals(blankToNull(patch.title()), asset.getTitle())) {
            asset.setTitle(blankToNull(patch.title()));
            changed.add("title");
        }
        if (patch.altText() != null && !Objects.equals(blankToNull(patch.altText()), asset.getAltText())) {
            String previousModel = asset.getAltTextModel();
            asset.setAltText(blankToNull(patch.altText()));
            if (previousModel != null && !previousModel.isBlank()) {
                aiAltTextRevised = true;
            }
            asset.setAltTextModel(null);
            changed.add("alt_text");
        }
        if (patch.copyright() != null && !Objects.equals(blankToNull(patch.copyright()), asset.getCopyright())) {
            asset.setCopyright(blankToNull(patch.copyright()));
            changed.add("copyright");
        }
        if (patch.tags() != null && !Objects.equals(blankToNull(patch.tags()), asset.getTags())) {
            asset.setTags(blankToNull(patch.tags()));
            changed.add("tags");
        }
        if (patch.folderPath() != null && !Objects.equals(blankToNull(patch.folderPath()), asset.getFolderPath())) {
            asset.setFolderPath(blankToNull(patch.folderPath()));
            changed.add("folder_path");
        }
        if (!changed.isEmpty()) {
            assetRepo.save(asset);
            writeMetadataAudit(asset, changed, aiAltTextRevised, actor);
        }
        return new MetadataResult(asset.getId(), asset.getCurrentVersion(), List.copyOf(changed), aiAltTextRevised);
    }

    /**
     * 批量搬移目录挂载（FR-AGT-MEDIA-005）：先整体校验（全部资产须在本站点）再写入，避免部分成功；
     * {@code folderPath} 为 {@code null} 表示移回根（解除目录挂载）。
     */
    public List<MoveResult> moveToFolder(UUID siteId, List<UUID> assetIds, String folderPath, String actor) {
        List<MediaAssetEntity> targets = new ArrayList<>(assetIds.size());
        for (UUID assetId : assetIds) {
            targets.add(requireInSite(siteId, assetId));
        }
        String to = blankToNull(folderPath);
        List<MoveResult> results = new ArrayList<>(targets.size());
        for (MediaAssetEntity asset : targets) {
            String from = asset.getFolderPath();
            if (!Objects.equals(from, to)) {
                asset.setFolderPath(to);
                assetRepo.save(asset);
            }
            results.add(new MoveResult(asset.getId(), asset.getTitle(), asset.getFilename(), from, to));
        }
        audit.write(actor, "media.move", "MediaAsset", String.join(",", assetIds.stream()
                .map(UUID::toString).toList()), siteId, "success",
            "媒体搬移 " + assetIds.size() + " 项 -> " + (to == null ? MediaFolderService.ROOT_PATH : to));
        return List.copyOf(results);
    }

    MediaAssetEntity requireInSite(UUID siteId, UUID assetId) {
        return assetRepo.findByIdAndSiteId(assetId, siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
    }

    private void writeMetadataAudit(MediaAssetEntity asset, List<String> changed, boolean aiAltTextRevised,
                                   String actor) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("assetId", asset.getId().toString());
            payload.put("changedKeys", String.join(",", changed));
            payload.put("aiAltTextRevised", aiAltTextRevised);
            audit.write(actor, "media.metadata.update", "MediaAsset", asset.getId().toString(), asset.getSiteId(),
                "success", payload.toString());
        } catch (RuntimeException ex) {
            // 审计失败不应回滚业务写入（与既有媒体侧审计口径一致，仅记录日志）
            org.slf4j.LoggerFactory.getLogger(MediaService.class)
                .warn("media metadata audit failed asset={} err={}", asset.getId(), ex.getMessage());
        }
    }

    /** 社区版媒体规模基线（规格 §4.1：2 万条 / 20GB，本地存储建议值）：越界只告警。 */
    private void observeMediaBaseline(long addingBytes, boolean newItem) {
        try {
            long totalGb = (assetRepo.sumFileSize() + Math.max(0, addingBytes)
                + (1024L * 1024L * 1024L) - 1) / (1024L * 1024L * 1024L);
            long items = assetRepo.countByStatus("ACTIVE") + (newItem ? 1 : 0);
            if (totalGb > 20 || items > 20_000) {
                org.slf4j.LoggerFactory.getLogger(MediaService.class).warn(
                    "community baseline: 媒体规模 items={} totalGb={} 已超过社区建议基线（20000 条 / 20GB）",
                    items, totalGb);
            }
        } catch (RuntimeException ignored) {
            // 基线观测不得影响媒体注册
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<AssetInfo> toInfos(List<MediaAssetEntity> assets) {
        Map<UUID, ContentTypeEntity> typeById = types(assets.stream()
            .map(MediaAssetEntity::getMediaTypeId).filter(java.util.Objects::nonNull).distinct().toList());
        return assets.stream().map(a -> AssetInfo.from(a, typeById)).toList();
    }

    private Map<UUID, ContentTypeEntity> types(UUID assetId) {
        return types(List.of(assetId));
    }

    private Map<UUID, ContentTypeEntity> types(List<UUID> typeIds) {
        if (typeIds.isEmpty()) return Map.of();
        return typeRepo.findAllById(typeIds).stream()
            .collect(Collectors.toMap(ContentTypeEntity::getId, Function.identity()));
    }

    MediaAssetEntity require(UUID id) {
        return assetRepo.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
    }
}
