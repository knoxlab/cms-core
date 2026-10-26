package com.powercms.cms.media;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.entity.MediaVersionEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaVersionRepository;
import com.powercms.cms.model.MediaClassifyService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 社区版本地盘媒体库：二进制写入 {@code powercms.media.storage-root}，资产 {@code source=LOCAL}、
 * 状态 {@code ACTIVE}（无转码）。视频只存原件，播放由 {@link com.powercms.cms.media.web.LocalMediaController} 以 Range 直出。
 */
@Service
public class LocalMediaStorage {

    private final Path storageRoot;
    private final long maxFileSize;
    private final MediaService mediaService;
    private final MediaAssetRepository assets;
    private final MediaVersionRepository versions;
    private final MediaClassifyService classify;
    private final AuditService audits;

    LocalMediaStorage(
        @Value("${powercms.media.storage-root:${java.io.tmpdir}/powercms-media}") String storageRoot,
        @Value("${powercms.media.max-file-size:104857600}") long maxFileSize,
        MediaService mediaService,
        MediaAssetRepository assets,
        MediaVersionRepository versions,
        MediaClassifyService classify,
        AuditService audits) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.mediaService = mediaService;
        this.assets = assets;
        this.versions = versions;
        this.classify = classify;
        this.audits = audits;
    }

    public record Stored(UUID assetId, String status) {}

    public Stored store(UUID siteId, String filename, String contentType, long declaredSize,
                        InputStream content, String folderPath) {
        return persist(siteId, null, filename, contentType, declaredSize, content, folderPath);
    }

    public Stored replace(UUID siteId, UUID assetId, String filename, String contentType,
                          long declaredSize, InputStream content, String folderPath) {
        requireInSite(siteId, assetId);
        return persist(siteId, assetId, filename, contentType, declaredSize, content, folderPath);
    }

    /** 按当前版本 storage key 解析本地文件；非 LOCAL 或路径越界时为空。 */
    public Optional<Path> resolveFile(UUID siteId, UUID assetId) {
        MediaAssetEntity asset = assets.findByIdAndSiteId(assetId, siteId).orElse(null);
        return asset == null ? Optional.empty() : resolveFile(asset);
    }

    public Optional<Path> resolveFile(MediaAssetEntity asset) {
        if (asset == null || asset.getSource() == null || !"LOCAL".equalsIgnoreCase(asset.getSource())) {
            return Optional.empty();
        }
        MediaVersionEntity version = versions
            .findByMediaAssetIdAndVersion(asset.getId(), asset.getCurrentVersion())
            .orElse(null);
        if (version == null || version.getStorageKey() == null) return Optional.empty();
        return resolveKey(version.getStorageKey());
    }

    public void deleteStored(UUID assetId) {
        MediaAssetEntity asset = assets.findById(assetId).orElse(null);
        if (asset == null) return;
        versions.findByMediaAssetIdAndVersion(assetId, asset.getCurrentVersion())
            .flatMap(v -> resolveKey(v.getStorageKey()))
            .ifPresent(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 账本删除不因磁盘清理失败回滚
                }
            });
    }

    private Stored persist(UUID siteId, UUID replaceId, String filename, String contentType,
                           long declaredSize, InputStream content, String folderPath) {
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "文件名不能为空");
        }
        if (declaredSize > maxFileSize) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "文件超过大小上限");
        }
        byte[] bytes = read(content);
        if (bytes.length > maxFileSize) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "文件超过大小上限");
        }
        String effectiveMime = contentType == null || contentType.isBlank()
            ? MediaClassifyService.EXTENSION_MIME.getOrDefault(extension(filename), "application/octet-stream")
            : contentType;
        MediaClassifyService.Classification classification =
            classify.resolve(siteId, filename, effectiveMime, bytes.length);
        if (classification.overMax()) {
            throw new BusinessException(ErrorCodes.MEDIA_FILE_TOO_LARGE,
                "文件超过媒体类型 " + classification.mediaTypeCode() + " 的大小上限");
        }
        String sha256 = sha256(bytes);
        String key = write(siteId, sha256, bytes);
        String folder = folderPath == null || folderPath.isBlank() ? classification.targetPath() : folderPath;
        UUID assetId;
        if (replaceId != null) {
            mediaService.replaceVersion(siteId, replaceId, filename, sha256, effectiveMime, bytes.length, key);
            assetId = replaceId;
        } else {
            UUID preallocated = assets.findBySiteIdAndContentHash(siteId, sha256)
                .map(MediaAssetEntity::getId).orElse(UUID.randomUUID());
            assetId = mediaService.register(siteId, preallocated, filename, sha256,
                effectiveMime, bytes.length, key).id();
        }
        MediaAssetEntity asset = assets.findById(assetId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
        asset.setSource("LOCAL");
        asset.setStatus("ACTIVE");
        asset.setOwnerSiteId(siteId);
        asset.setMediaTypeId(classification.mediaTypeId());
        asset.setKind(classification.kind());
        asset.setFolderPath(folder);
        assets.save(asset);
        audits.write(AuditService.currentActor(), "media.upload", "MediaAsset", assetId.toString(), siteId,
            "SUCCESS", "source=LOCAL file=" + filename + " bytes=" + bytes.length);
        return new Stored(assetId, "ACTIVE");
    }

    private String write(UUID siteId, String sha256, byte[] bytes) {
        String prefix = sha256.substring(0, 2).toLowerCase(Locale.ROOT);
        String relative = siteId + "/" + prefix + "/" + sha256.toLowerCase(Locale.ROOT);
        Path path = storageRoot.resolve(relative).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "存储路径非法");
        }
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.write(path, bytes);
            }
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "本地媒体写入失败");
        }
        return "local://" + relative;
    }

    private Optional<Path> resolveKey(String storageKey) {
        if (storageKey == null || !storageKey.startsWith("local://")) return Optional.empty();
        String relative = storageKey.substring("local://".length());
        Path path = storageRoot.resolve(relative).normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) return Optional.empty();
        return Optional.of(path);
    }

    private void requireInSite(UUID siteId, UUID assetId) {
        assets.findByIdAndSiteId(assetId, siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
    }

    private static byte[] read(InputStream content) {
        try {
            return content.readAllBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "读取上传流失败");
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
