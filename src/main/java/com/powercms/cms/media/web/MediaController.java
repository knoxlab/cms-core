package com.powercms.cms.media.web;

import com.powercms.cms.media.MediaService;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.entity.MediaVersionEntity;
import com.powercms.cms.media.repo.MediaVersionRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 媒体库管理 API（FR-ASSET-001~006）。
 *
 * <p>社区版为本地存储模式（{@code powercms.media.storage-root}）：不含外部媒资中台（Knox-Media）
 * 的映射列、转码产物与物理删除编排，媒体资产的登记/替换/归档/删除均在本地完成。
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/media")
class MediaController {

    private final MediaService service;
    private final MediaVersionRepository versionRepo;
    private final com.powercms.cms.media.repo.MediaAssetRepository assetRepo;
    private final com.powercms.cms.audit.AuditService audit;

    MediaController(MediaService service,
                    MediaVersionRepository versionRepo,
                    com.powercms.cms.media.repo.MediaAssetRepository assetRepo,
                    com.powercms.cms.audit.AuditService audit) {
        this.service = service;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.audit = audit;
    }

    /**
     * 媒体库行（FR-ASSET-001）：社区版不接外部媒资中台，{@code knoxMediaId}/{@code endpointName}
     * 恒为 {@code null}，字段保留以维持前端契约稳定。
     */
    public record AssetRow(MediaService.AssetInfo asset, String knoxMediaId, String endpointName) {}

    /** 分页信封：与前端 `{items,total,page,size,hasMore}` 约定一致。 */
    public record MediaPageResponse(List<AssetRow> items, int total, int page, int size, boolean hasMore) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping
    List<AssetRow> list(@PathVariable UUID siteId,
                        @RequestParam(required = false) String kind,
                        @RequestParam(required = false) UUID mediaTypeId,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) String folderPath) {
        return toRows(service.list(siteId, kind, mediaTypeId, q, status, folderPath));
    }

    /**
     * 媒体库分页行：供媒体库列表/缩略图视图滚动加载与表格视图服务端分页。
     *
     * <p>{@code sort} 见 {@link com.powercms.cms.media.MediaSort}（未知值回落文件名升序）；
     * {@code size} 上限 {@link com.powercms.cms.media.MediaSort#MAX_PAGE_SIZE}，防止变相全量拉取。
     */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/page")
    MediaPageResponse page(@PathVariable UUID siteId,
                           @RequestParam(required = false) String kind,
                           @RequestParam(required = false) UUID mediaTypeId,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) String sort,
                           @RequestParam(required = false) String folderPath,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "24") int size) {
        var slice = service.page(siteId, kind, mediaTypeId, q, status, sort, page, size, folderPath);
        List<MediaService.AssetInfo> assets = slice.items().stream().map(MediaService.SearchHit::asset).toList();
        return new MediaPageResponse(toRows(assets), slice.total(), slice.page(), slice.size(), slice.hasMore());
    }

    private List<AssetRow> toRows(List<MediaService.AssetInfo> assets) {
        return assets.stream().map(a -> new AssetRow(a, null, null)).toList();
    }

    @PreAuthorize("@siteAuthorization.media(authentication, #id, 'read')")
    @GetMapping("/{id}")
    MediaService.AssetInfo get(@PathVariable UUID siteId, @PathVariable UUID id) { return service.get(id); }

    /** 版本历史行（FR-ASSET-005）：资产产物随版本版本化，历史 Release 回放据此读取。 */
    public record VersionDto(int version, String filename, String mimeType, long fileSize,
                             String contentHash, Instant storedAt, String artifactsJson) {}

    /** 详情抽屉数据（FR-ASSET-003）：全量元数据 + 处理步骤时间线 + 版本历史 + 播放产物。 */
    public record DetailResponse(MediaService.AssetInfo asset, String knoxMediaId, String endpointName,
                                 List<String> processingSteps, List<VersionDto> versions,
                                 String playPlan, String playbackJson,
                                 /** 封面地址（{@code /media-thumb/{siteId}/{assetId}} 本站代理）；无封面概念时为 null。 */
                                 String posterUrl) {}

    @PreAuthorize("@siteAuthorization.media(authentication, #id, 'read')")
    @GetMapping("/{id}/detail")
    DetailResponse detail(@PathVariable UUID siteId, @PathVariable UUID id) {
        MediaService.AssetInfo asset = service.get(id);
        List<VersionDto> versions = versionRepo.findByMediaAssetIdOrderByVersionDesc(id).stream()
            .map(v -> new VersionDto(v.getVersion(), v.getFilename(), v.getMimeType(), v.getFileSize(),
                v.getContentHash(), v.getStoredAt(), v.getArtifactsJson()))
            .toList();
        String kind = asset.kind();
        String posterUrl = "image".equals(kind) || "video".equals(kind) || "document".equals(kind)
            ? "/media-thumb/" + siteId + "/" + id : null;
        return new DetailResponse(asset, null, null, List.of(), versions, null, null, posterUrl);
    }

    record RegisterReq(@NotBlank String filename, @NotBlank String contentHash,
                       @NotBlank String mimeType, @Positive long fileSize,
                       @NotBlank String storageKey) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PostMapping
    ResponseEntity<MediaService.AssetInfo> register(@PathVariable UUID siteId,
                                                      @Valid @RequestBody RegisterReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.register(siteId, req.filename(), req.contentHash(),
                req.mimeType(), req.fileSize(), req.storageKey()));
    }

    @PreAuthorize("@siteAuthorization.media(authentication, #id, 'write')")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID siteId, @PathVariable UUID id) {
        service.delete(id); return ResponseEntity.noContent().build();
    }

    /**
     * FR-ASSET-006 资产权属与可引用有效期编辑：版权/许可（放 metadata）+ 有效期窗口。
     * null 表示不限；起 &gt; 止视为非法。变更落审计，便于合规追溯。
     */
    public record RightsView(UUID assetId, String copyright, Instant validFrom, Instant validUntil,
                             boolean expired, String license) {}

    public record RightsRequest(String copyright, Instant validFrom, Instant validUntil, String license) {}

    @PreAuthorize("@siteAuthorization.media(authentication, #id, 'write')")
    @PutMapping("/{id}/rights")
    @org.springframework.transaction.annotation.Transactional
    RightsView updateRights(@PathVariable UUID siteId, @PathVariable UUID id,
                            @RequestBody RightsRequest req) {
        MediaAssetEntity asset = assetRepo.findById(id)
            .filter(a -> a.getSiteId().equals(siteId))
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
        if (req.validFrom() != null && req.validUntil() != null
            && req.validFrom().isAfter(req.validUntil())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "有效期起不能晚于止");
        }
        asset.setCopyright(blankToNull(req.copyright()));
        asset.setValidFrom(req.validFrom());
        asset.setValidUntil(req.validUntil());
        assetRepo.save(asset);
        audit.write(com.powercms.cms.audit.AuditService.currentActor(), "media.rights.updated",
            "MediaAsset", id.toString(), siteId, "success",
            "validFrom=" + req.validFrom() + " validUntil=" + req.validUntil());
        return rightsView(asset);
    }

    @PreAuthorize("@siteAuthorization.media(authentication, #id, 'read')")
    @GetMapping("/{id}/rights")
    RightsView getRights(@PathVariable UUID siteId, @PathVariable UUID id) {
        MediaAssetEntity asset = assetRepo.findById(id)
            .filter(a -> a.getSiteId().equals(siteId))
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
        return rightsView(asset);
    }

    private static RightsView rightsView(MediaAssetEntity a) {
        Instant now = Instant.now();
        boolean expired = (a.getValidFrom() != null && now.isBefore(a.getValidFrom()))
            || (a.getValidUntil() != null && now.isAfter(a.getValidUntil()));
        return new RightsView(a.getId(), a.getCopyright(), a.getValidFrom(), a.getValidUntil(),
            expired, licenseOf(a.getMetadata()));
    }

    /** 许可信息存在 metadata.powercms.license（站点包迁移/导入时写入的同口径字段）。 */
    private static String licenseOf(String metadata) {
        if (metadata == null || metadata.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode pc = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(metadata).path("powercms");
            com.fasterxml.jackson.databind.JsonNode lic = pc.get("license");
            return lic == null || lic.isNull() ? null : lic.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
