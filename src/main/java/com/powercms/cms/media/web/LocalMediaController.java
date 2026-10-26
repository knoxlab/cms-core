package com.powercms.cms.media.web;

import com.powercms.cms.media.LocalMediaStorage;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * 下载/内嵌入口：与 {@link #preview} 同源，浏览器 {@code <video>} 与直链下载都走这里。
     *
     * <p>{@code Range} 不在参数上取——Spring MVC 会自己读该头并把 200 改写成 206。
     */
    @GetMapping("/media-file/{siteId}/{assetId}")
    ResponseEntity<Resource> file(@PathVariable UUID siteId, @PathVariable UUID assetId) {
        return serve(siteId, assetId);
    }

    /** 缩略图：社区版没有转码产物，图片直接回原件，其余类型同样回原件（前端按 MIME 处理）。 */
    @GetMapping("/media-thumb/{siteId}/{assetId}")
    ResponseEntity<Resource> thumb(@PathVariable UUID siteId, @PathVariable UUID assetId) {
        return serve(siteId, assetId);
    }

    /** 预览页：直接回原件，浏览器按 MIME 播放/展示。 */
    @GetMapping("/media-preview/{siteId}/{assetId}")
    ResponseEntity<Resource> preview(@PathVariable UUID siteId, @PathVariable UUID assetId) {
        return serve(siteId, assetId);
    }

    /**
     * 直出原件（整段 200）。
     *
     * <p>Range 请求交给 Spring MVC：当方法返回类型是 {@code ResponseEntity<Resource>}
     * 且响应状态为 200 时，{@code AbstractMessageConverterMethodProcessor} 会读取
     * {@code Range} 头、转成 {@code List<ResourceRegion>}，再用
     * {@link org.springframework.http.converter.ResourceRegionHttpMessageConverter}
     * 输出 206。
     *
     * <p>不能自己切 {@code ResourceRegion}：那样返回类型必须是
     * {@code ResponseEntity<?>}（类型擦除后 targetType 为通配符），
     * {@code ResourceRegionHttpMessageConverter#canWrite(Type, Class, MediaType)}
     * 对 {@code WildcardType} 直接返回 {@code false}，于是报
     * {@code HttpMessageNotWritableException: No converter for [class ...ResourceRegion]} → 500。
     */
    private ResponseEntity<Resource> serve(UUID siteId, UUID assetId) {
        MediaAssetEntity asset = assets.findByIdAndSiteId(assetId, siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体资产不存在"));
        Path path = storage.resolveFile(asset)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体文件不存在"));
        Resource resource = new FileSystemResource(path);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaTypeOf(asset, path));
        // Content-Length 不手动设置：整段由 ResourceHttpMessageConverter 补齐，
        // 206 由 Spring 按分片重算，手动写整段长度会让 Partial Content 断流。
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, inline(asset.getFilename()));
        return ResponseEntity.ok().headers(headers).body(resource);
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

    /**
     * 构造 RFC 6266 的 {@code Content-Disposition: inline}。
     *
     * <p>HTTP 头值只能承载 ISO-8859-1（0–255）字符，直接把中文文件名塞进
     * {@code filename} 会被 Tomcat 判为非法并<b>整个头丢弃</b>：
     * {@code The Unicode character [统] at code point [32,479] cannot be encoded ...}，
     * 下载名随即丢失。故按 RFC 6266 同时给出两项：ASCII 回落名 {@code filename}
     * 供老客户端使用，{@code filename*=UTF-8''…} 携带原始名供现代浏览器优先采用。
     */
    private static String inline(String filename) {
        String name = filename == null || filename.isBlank() ? "media" : filename;
        return "inline; filename=\"" + asciiFallback(name) + "\"; filename*=UTF-8''" + rfc5987(name);
    }

    /** ASCII 回落名：非可打印 ASCII（中文、控制字符、CR/LF、引号、反斜杠）一律替换为下划线。 */
    private static String asciiFallback(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean printableAscii = ch >= 0x20 && ch <= 0x7E;
            boolean quotedOrEscaped = ch == '"' || ch == '\\';
            sb.append(printableAscii && !quotedOrEscaped ? ch : '_');
        }
        return sb.isEmpty() ? "_" : sb.toString();
    }

    /** RFC 5987 {@code ext-value}：UTF-8 百分号编码，仅放行 {@code attr-char}。 */
    private static String rfc5987(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte raw : bytes) {
            int c = raw & 0xFF;
            if (isAttrChar(c)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    private static boolean isAttrChar(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || "!#$&+-.^_`|~".indexOf(c) >= 0;
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
}
