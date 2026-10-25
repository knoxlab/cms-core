package com.powercms.cms.media;

import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.model.MediaClassifyService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * kind/MIME 存量纠偏（FR-CM-MEDIA-040 扩展）：Windows 浏览器把 .ts/.m2ts 等传输流
 * MIME 误报为 text/plain，早期版本按声明值归类导致音视频落进 media_document。
 * 启动时按扩展名表纠正 kind、mime_type，并重挂到对应媒体类型；修正后发
 * MediaAssetReadyEvent 驱动 MEDIA 内容项重建字段值与索引（幂等）。
 */
@Component
public class MediaKindBackfillJob {

    public static final Logger log = LoggerFactory.getLogger(MediaKindBackfillJob.class);

    private static final Set<String> VIDEO_EXTS = Set.of(
        "mp4", "webm", "mov", "m4v", "ts", "m2t", "m2ts", "mts", "avi", "mkv", "flv", "wmv", "3gp",
        "mpg", "mpeg");
    private static final Set<String> AUDIO_EXTS = Set.of(
        "mp3", "wav", "aac", "ogg", "flac", "m4a", "opus", "wma");
    /** 字体扩展名（2026-09-12 新增 media_font 后，存量落在 other 的字体需重挂）。 */
    private static final Set<String> FONT_EXTS = Set.of(
        "ttf", "otf", "woff", "woff2", "ttc");

    private final MediaAssetRepository assetRepo;
    private final com.powercms.cms.model.repo.ContentTypeRepository typeRepo;
    private final com.powercms.cms.model.MediaClassifyService classify;
    private final ApplicationEventPublisher events;
    private com.powercms.cms.model.MediaTypeSeedService seeds;

    public MediaKindBackfillJob(MediaAssetRepository assetRepo,
                                com.powercms.cms.model.repo.ContentTypeRepository typeRepo,
                                com.powercms.cms.model.MediaClassifyService classify,
                                ApplicationEventPublisher events) {
        this.assetRepo = assetRepo;
        this.typeRepo = typeRepo;
        this.classify = classify;
        this.events = events;
    }

    /** 文档 MIME 白名单升级。测试构造不注入时跳过，避免改既有纠偏用例的依赖。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSeeds(com.powercms.cms.model.MediaTypeSeedService seeds) {
        this.seeds = seeds;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        if (seeds != null) {
            seeds.upgradeDocumentAccepts();
        }
        classifyNeverClassified();
        List<MediaAssetEntity> suspects = assetRepo.findByKindIn(List.of("document", "other"));
        if (suspects.isEmpty()) return;
        // 每站点一次加载 MEDIA 类型，按 kind 建立重挂映射
        Map<UUID, Map<String, ContentTypeEntity>> mediaTypesBySite = suspects.stream()
            .map(MediaAssetEntity::getSiteId).distinct()
            .collect(Collectors.toMap(s -> s, this::mediaTypesByKind));
        int fixed = 0;
        for (MediaAssetEntity asset : suspects) {
            String extMime = MediaClassifyService.extensionMimeOf(asset.getFilename());
            String kind = null;
            if (extMime != null) {
                kind = extMime.startsWith("video/") && hasExt(asset.getFilename(), VIDEO_EXTS) ? "video"
                    : extMime.startsWith("audio/") && hasExt(asset.getFilename(), AUDIO_EXTS) ? "audio"
                    : hasExt(asset.getFilename(), FONT_EXTS) ? "font"
                    : null;
            }
            if (kind == null || kind.equals(asset.getKind())) {
                if ("other".equals(asset.getKind()) && reclassifyDocument(asset, mediaTypesBySite)) {
                    fixed++;
                }
                continue;
            }
            String previousKind = asset.getKind();
            ContentTypeEntity target = mediaTypesBySite
                .getOrDefault(asset.getSiteId(), Map.of()).get(kind);
            if (target == null) {
                log.warn("media kind backfill skipped, no target type site={} asset={} kind={}",
                    asset.getSiteId(), asset.getId(), kind);
                continue;
            }
            asset.setKind(kind);
            asset.setMimeType(extMime);
            asset.setMediaTypeId(target.getId());
            assetRepo.save(asset);
            fixed++;
            // 内容项字段值按纠正后的系统字段重建（thumbnailUrl_s 等随刷新进入索引）
            events.publishEvent(new MediaAssetReadyEvent(asset.getSiteId(), asset.getId()));
            log.info("media kind backfilled asset={} filename={} {} -> {} ({})",
                asset.getId(), asset.getFilename(), previousKind, kind, extMime);
        }
        if (fixed > 0) {
            log.info("media kind backfill done: {} assets corrected", fixed);
        }
    }

    /**
     * 把落在 {@code other} 上、但按当前白名单应归文档的资产改挂到 {@code media_document}。
     *
     * <p>不改存储路径：文件已经在 {@code /other/} 下，搬路径会让 Knox 对象对不上。
     * 媒体库看的是 {@code kind} 与媒体类型，不是目录名。
     */
    private boolean reclassifyDocument(MediaAssetEntity asset,
                                       Map<UUID, Map<String, ContentTypeEntity>> mediaTypesBySite) {
        ContentTypeEntity target = mediaTypesBySite
            .getOrDefault(asset.getSiteId(), Map.of()).get("document");
        if (target == null) return false;
        String mime = asset.getMimeType();
        if (mime == null || mime.isBlank() || "application/octet-stream".equals(mime)) {
            String extMime = MediaClassifyService.extensionMimeOf(asset.getFilename());
            if (extMime != null) mime = extMime;
        }
        if (mime == null || mime.isBlank()) return false;
        try {
            var c = classify.resolve(asset.getSiteId(), asset.getFilename(), mime, asset.getFileSize());
            if (!"document".equals(c.kind())) return false;
            String previous = asset.getKind();
            asset.setKind("document");
            asset.setMimeType(mime);
            asset.setMediaTypeId(target.getId());
            assetRepo.save(asset);
            events.publishEvent(new MediaAssetReadyEvent(asset.getSiteId(), asset.getId()));
            log.info("media kind backfilled asset={} filename={} {} -> document ({})",
                asset.getId(), asset.getFilename(), previous, mime);
            return true;
        } catch (RuntimeException ex) {
            log.warn("document reclassify skipped asset={} err={}", asset.getId(), ex.getMessage());
            return false;
        }
    }

    /**
     * 未归类存量补齐（FR-KNX-015 存量修复）：早期分片上传通道缺失自动归类，
     * 资产 media_type_id/kind 均为空，永远不会同步 MEDIA 内容项（内容搜索不可见）。
     * 启动时按扩展名/MIME 重新归类并补齐 folderPath，发 Registered 事件驱动内容项创建。
     */
    private void classifyNeverClassified() {
        List<MediaAssetEntity> unclassified = assetRepo.findByMediaTypeIdIsNullAndKindIsNull();
        if (unclassified.isEmpty()) return;
        log.info("media classify backfill: {} unclassified assets found", unclassified.size());
        int fixed = 0;
        for (MediaAssetEntity asset : unclassified) {
            try {
                String mime = MediaClassifyService.extensionMimeOf(asset.getFilename());
                if (mime == null || mime.isBlank()) {
                    mime = asset.getMimeType() == null ? "" : asset.getMimeType();
                }
                var c = classify.resolve(asset.getSiteId(), asset.getFilename(), mime, asset.getFileSize());
                String targetDir = classify.resolvePath(c.storagePathPattern(), c.kind(),
                    asset.getSiteId(), asset.getId());
                asset.setKind(c.kind());
                if (asset.getMimeType() == null || asset.getMimeType().isBlank()) {
                    asset.setMimeType(mime);
                }
                asset.setMediaTypeId(c.mediaTypeId());
                if (asset.getFolderPath() == null || asset.getFolderPath().isBlank()) {
                    asset.setFolderPath(targetDir);
                }
                assetRepo.save(asset);
                fixed++;
                // 归类落库即补齐建账：Registered 事件驱动 MEDIA 内容项创建（与上传链路一致）
                events.publishEvent(new MediaAssetRegisteredEvent(asset.getSiteId(), asset.getId()));
                log.info("media classify backfilled asset={} filename={} kind={} type={}",
                    asset.getId(), asset.getFilename(), c.kind(), c.mediaTypeCode());
            } catch (Exception ex) {
                log.warn("media classify backfill failed asset={} err={}", asset.getId(), ex.getMessage());
            }
        }
        if (fixed > 0) {
            log.info("media classify backfill done: {} assets classified", fixed);
        }
    }

    private Map<String, ContentTypeEntity> mediaTypesByKind(UUID siteId) {
        return typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA").stream()
            .filter(t -> t.getMediaKind() != null)
            .collect(Collectors.toMap(ContentTypeEntity::getMediaKind, t -> t, (a, b) -> a));
    }

    private static boolean hasExt(String filename, Set<String> exts) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return exts.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
