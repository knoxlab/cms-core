package com.powercms.cms.media;

import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 社区版本地媒体上传与直出：上传落 {@code powercms.media.storage-root}，
 * {@code /media-file}、{@code /media-thumb}、{@code /media-preview} 按资产当前版本读取。
 *
 * <p>这些交付端点与 Knox-Media 的播放代理无关：视频只出原件（支持 Range），
 * 图片直出，文档由浏览器按 MIME 处理。
 */
@RestController
public class LocalMediaController {

    private final LocalMediaStorage storage;
    private final MediaAssetRepository assets;

    LocalMediaController(LocalMediaStorage storage, MediaAssetRepository assets) {
        this.storage = storage;
        this.assets = assets;
    }

    public record UploadView(UUID assetId, String status) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PostMapping("/api/v1/sites/{siteId}/media/upload")
    UploadView upload(@PathVariable UUID siteId,
                      @RequestParam("file") MultipartFile file,
                      @RequestParam(value = "folderPath", required = false) String folderPath) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "上传文件不能为空");
        }
        var stored = storage.store(siteId, file.getOriginalFilename(), file.getContentType(),
            file.getSize(), file.getInputStream(), folderPath);
        return new UploadView(stored.assetId(), stored.status());
    }

    @PreAuthorize("@siteAuthorization.media(authentication, #assetId, 'write')")
    @PostMapping("/api/v1/sites/{siteId}/media/{assetId}/replace")
    UploadView replace(@PathVariable UUID siteId, @PathVariable UUID assetId,
                       @RequestParam("file") MultipartFile file,
                       @RequestParam(value = "folderPath", required = false) String folderPath) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "上传文件不能为空");
        }
        var stored = storage.replace(siteId, assetId, file.getOriginalFilename(), file.getContentType(),
            file.getSize(), file.getInputStream(), folderPath);
        return new UploadView(stored.assetId(), stored.status());
    }

    @GetMapping("/media-file/{siteId}/{assetId}")
    ResponseEntity<?> file(@PathVariable UUID siteId, @PathVariable UUID assetId,
                           @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        return serve(siteId, assetId, range);
    }

    /** 缩略图：社区版没有转码产物，图片直接回原件，其余类型同样回原件（前端按 MIME 处理）。 */
    @GetMapping("/media-thumb/{siteId}/{assetId}")
    ResponseEntity<?> thumb(@PathVariable UUID siteId, @PathVariable UUID assetId) {
        return serve(siteId, assetId, null);
    }

    /** 预览页：直接回原件，浏览器按 MIME 播放/展示。 */
    @GetMapping("/media-preview/{siteId}/{assetId}")
    ResponseEntity<?> preview(@PathVariable UUID siteId, @PathVariable UUID assetId,
                              @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        return serve(siteId, assetId, range);
    }

    private ResponseEntity<?> serve(UUID siteId, UUID assetId, String rangeHeader) {
        MediaAssetEntity asset = assets.findByIdAndSiteId(assetId, siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
        Path path = storage.resolveFile(asset)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体文件不存在"));
        Resource resource = new FileSystemResource(path);
        MediaType mediaType = mediaTypeOf(asset, path);
        try {
            long length = resource.contentLength();
            List<HttpRange> ranges = rangeHeader == null || rangeHeader.isBlank()
                ? List.of() : HttpRange.parseRanges(rangeHeader);
            if (ranges.isEmpty()) {
                return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, inline(asset.getFilename()))
                    .body(resource);
            }
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(length);
            long end = range.getRangeEnd(length);
            long count = end - start + 1;
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
                .header(HttpHeaders.CONTENT_DISPOSITION, inline(asset.getFilename()))
                .body(new ResourceRegion(resource, start, count));
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "媒体文件读取失败");
        }
    }

    private static MediaType mediaTypeOf(MediaAssetEntity asset, Path path) {
        try {
            if (asset.getMimeType() != null && !asset.getMimeType().isBlank()) {
                return MediaType.parseMediaType(asset.getMimeType());
            }
            String probed = Files.probeContentType(path);
            if (probed != null) return MediaType.parseMediaType(probed);
        } catch (RuntimeException | IOException ignored) {
            // 回落到二进制流
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static String inline(String filename) {
        String name = filename == null || filename.isBlank() ? "media" : filename;
        return "inline; filename=\"" + name.replace("\"", "") + "\"";
    }
}
