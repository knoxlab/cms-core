package com.powercms.cms.model;

import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 媒体自动归类与路径宏解析（FR-CM-MEDIA-040~044/050~053，对齐 dotCMS accept 优先级）。
 *
 * <p>归类优先级：精确匹配（MIME 或扩展名）＞ 通配符匹配（如 image/星号）＞ 无限制类型
 * （未配置 accept 或 星/星）；同优先级冲突时取 accept 限制最严格者（可接受 pattern 最少），
 * 仍冲突按类型 code 字母序。归类只决定媒体类型与目标路径，不改变共享库可见性（FR-CM-MEDIA-044）。
 */
@Service
public class MediaClassifyService {

    public static final Map<String, String> EXTENSION_MIME = extensionMime();

    private final ContentTypeRepository typeRepo;

    MediaClassifyService(ContentTypeRepository typeRepo) {
        this.typeRepo = typeRepo;
    }

    /** 自动归类结果（FR-CM-MEDIA-041/051）。 */
    public record Classification(UUID mediaTypeId, String mediaTypeCode, String kind,
                                 String targetPath, String storagePathPattern,
                                 boolean overMax, Long maxFileLength) {}

    /**
     * 按 accept 优先级归类。targetPath 基于当前日期与随机 objectId 生成，供上传前预览；
     * 实际上传时用 {@link #resolvePath} 以真实资产 id 重新解析（storagePathPattern 携带在结果中）。
     */
    public Classification resolve(UUID siteId, String filename, String mimeType, long size) {
        String mime = effectiveMime(filename, mimeType);
        List<ContentTypeEntity> mediaTypes =
            typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA");
        List<Candidate> candidates = new ArrayList<>();
        for (ContentTypeEntity type : mediaTypes) {
            int level = matchLevel(type.getMediaAccept(), mime);
            if (level >= 0) {
                List<String> tokens = acceptTokens(type.getMediaAccept());
                candidates.add(new Candidate(type, level, tokens.size()));
            }
        }
        Candidate best =
            candidates.stream()
                .min(
                    Comparator.comparingInt(Candidate::level)
                        .thenComparingInt(Candidate::acceptCount)
                        .thenComparing(c -> c.type().getCode()))
                .orElseThrow(
                    () ->
                        new BusinessException(
                            ErrorCodes.MEDIA_TYPE_NOT_FOUND,
                            "没有匹配的媒体内容类型，且站点未提供 media_other"));
        ContentTypeEntity type = best.type();
        String pattern =
            type.getStoragePathPattern() == null || type.getStoragePathPattern().isBlank()
                ? MediaTypeSeedService.DEFAULT_STORAGE_PATTERN
                : type.getStoragePathPattern();
        String targetPath = resolvePath(pattern, type.getMediaKind(), siteId, UUID.randomUUID());
        return new Classification(
            type.getId(),
            type.getCode(),
            type.getMediaKind(),
            targetPath,
            pattern,
            type.getMediaMaxFileLength() != null && size > type.getMediaMaxFileLength(),
            type.getMediaMaxFileLength());
    }

    /**
     * 路径宏解析（FR-CM-MEDIA-050/051）：{yyyy}/{mm}/{dd}/{objectId}/{kind}/{siteId}。
     */
    public String resolvePath(String pattern, String kind, UUID siteId, UUID objectId) {
        if (pattern == null || pattern.isBlank()) {
            pattern = MediaTypeSeedService.DEFAULT_STORAGE_PATTERN;
        }
        LocalDate date = LocalDate.now();
        String path =
            pattern
                .replace("{yyyy}", String.valueOf(date.getYear()))
                .replace("{mm}", String.format("%02d", date.getMonthValue()))
                .replace("{dd}", String.format("%02d", date.getDayOfMonth()))
                .replace("{objectId}", objectId.toString())
                .replace("{kind}", kind == null ? "other" : kind)
                .replace("{siteId}", siteId.toString());
        if (path.matches(".*\\{[^}]+}.*")) {
            throw new BusinessException(
                ErrorCodes.VALIDATION_FAILED, "存储路径模式包含不支持的宏: " + pattern);
        }
        return path;
    }

    /** MIME 白名单校验（FR-CM-MEDIA-040）：逗号分隔，元素须为 MIME 类型或 glob（如 image 通配、任意类型）。 */
    public static void validateAccept(String accept) {
        if (accept == null || accept.isBlank()) return;
        for (String token : accept.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            if (!t.matches("[a-z0-9.+*-]+/[a-z0-9.+*-]+")) {
                throw new BusinessException(
                    ErrorCodes.MEDIA_ACCEPT_INVALID, "媒体 accept 包含非法 MIME 白名单: " + token);
            }
        }
    }

    /** 存储路径模式校验（FR-CM-MEDIA-050）：仅允许 {yyyy}/{mm}/{dd}/{objectId}/{kind}/{siteId} 宏。 */
    public static void validateStoragePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) return;
        String after =
            pattern
                .replace("{yyyy}", "1")
                .replace("{mm}", "1")
                .replace("{dd}", "1")
                .replace("{objectId}", "1")
                .replace("{kind}", "1")
                .replace("{siteId}", "1");
        if (after.matches(".*\\{[^}]+}.*")) {
            throw new BusinessException(
                ErrorCodes.VALIDATION_FAILED, "存储路径模式包含不支持的宏: " + pattern);
        }
    }

    /**
     * 匹配级别：0 精确、1 通配、2 无限制；不匹配返回 -1。
     */
    static int matchLevel(String accept, String mime) {
        if (accept == null || accept.isBlank()) return 2;
        List<String> tokens = acceptTokens(accept);
        if (tokens.isEmpty()) return 2;
        boolean wildcard = false;
        for (String token : tokens) {
            if ("*/*".equals(token)) return 2;
            if (token.contains("*")) {
                if (glob(token, mime)) wildcard = true;
            } else if (token.equalsIgnoreCase(mime)) {
                return 0;
            }
        }
        return wildcard ? 1 : -1;
    }

    static List<String> acceptTokens(String accept) {
        if (accept == null || accept.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String token : accept.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
    }

    private static boolean glob(String pattern, String mime) {
        if (mime == null) return false;
        String regex =
            pattern.toLowerCase(Locale.ROOT).replace(".", "\\.").replace("*", "[^/]*");
        return mime.toLowerCase(Locale.ROOT).matches(regex);
    }

    /** 有效 MIME：优先声明值；缺失或空时按扩展名推导。 */
    static String effectiveMime(String filename, String declared) {
        if (declared != null && !declared.isBlank() && !"application/octet-stream".equals(declared)) {
            // Windows/浏览器常见误报：.ts/.m2ts 等被探测成 text/plain 或 application/octet-stream。
            // 已知音视频扩展名与声明值冲突时以扩展名表为准，避免传输流视频被归入文档类。
            String extMime = extensionMimeOf(filename);
            if (extMime != null && (declared.startsWith("text/plain") || "application/octet-stream".equals(declared))) {
                return extMime;
            }
            return declared.trim().toLowerCase(Locale.ROOT);
        }
        if (filename == null || filename.isBlank()) return declared == null ? "" : declared;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return declared == null ? "" : declared;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSION_MIME.getOrDefault(ext, declared == null ? "" : declared);
    }

    /** 按文件名扩展名推导 MIME；未知扩展返回 null。 */
    public static String extensionMimeOf(String filename) {
        if (filename == null || filename.isBlank()) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return EXTENSION_MIME.get(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 上传基础安全校验（FR-KNX-010）：扩展名白名单 + 声明/推导 MIME 一致性。
     * 返回可用于归类与落库的有效 MIME。
     *
     * <ul>
     *   <li>扩展名在 {@link #EXTENSION_MIME} 白名单内且其 MIME 大类在 allowedGroups 中 → 通过；
     *   推导 MIME 大类不在允许组时拒绝（未知/受限扩展名）。</li>
     *   <li>扩展名已知且声明 MIME 明确时做一致性检查：声明 MIME 大类与推导 MIME 大类不同即拒绝
     *   （伪装扩展名，如 .jpg 内容实为 PHP）；Windows 常见误报 text/plain /
     *   application/octet-stream 视为占位跳过一致性检查（.ts/.m2ts 等传输流）。</li>
     *   <li>扩展名未知时：仅当声明 MIME 明确且在允许组内才放行，否则拒绝。</li>
     * </ul>
     *
     * @param allowedGroups 逗号分隔允许的 MIME 大类（image/audio/video/document/font，规格
     *                      powercms.media.allowed-mime-groups 默认值）
     */
    public static String validateUploadAllowed(String filename, String declaredMime, String allowedGroups) {
        Set<String> allowed = new java.util.HashSet<>();
        for (String g : (allowedGroups == null ? "" : allowedGroups).split(",")) {
            if (!g.isBlank()) allowed.add(g.trim().toLowerCase(Locale.ROOT));
        }
        if (allowed.isEmpty()) {
            allowed.addAll(Set.of("image", "audio", "video", "document", "font"));
        }
        String extMime = extensionMimeOf(filename);
        if (extMime != null) {
            if (!allowed.contains(mimeGroup(extMime))) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "文件扩展名类型不在允许上传范围内: " + extensionOf(filename));
            }
            if (declaredMime != null && !declaredMime.isBlank()
                && !"application/octet-stream".equals(declaredMime)
                && !declaredMime.startsWith("text/plain")) {
                // Windows/浏览器对未知二进制常报 text/plain 或 octet-stream：跳过一致性，按扩展名归类
                String declaredGroup = mimeGroup(declaredMime);
                if (!allowed.contains(declaredGroup)) {
                    throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                        "声明 MIME 类型不在允许上传范围内: " + declaredMime);
                }
                if (!declaredGroup.equals(mimeGroup(extMime))) {
                    throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                        "声明 MIME 与文件扩展名不一致，疑似伪装文件: 声明=" + declaredMime
                            + " 扩展名=" + extensionOf(filename));
                }
            }
            return extMime;
        }
        // 扩展名未知：必须有明确且允许的声明 MIME
        if (declaredMime == null || declaredMime.isBlank()
            || "application/octet-stream".equals(declaredMime)
            || declaredMime.startsWith("text/plain")) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "无法识别文件类型：扩展名不在白名单且 MIME 缺失或为占位值");
        }
        if (!allowed.contains(mimeGroup(declaredMime))) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "声明 MIME 类型不在允许上传范围内: " + declaredMime);
        }
        return declaredMime.trim().toLowerCase(Locale.ROOT);
    }

    /** MIME 大类（image/audio/video/document/font；application·text→document，其他按其自身大类）。 */
    static String mimeGroup(String mime) {
        if (mime == null || mime.isBlank()) return "unknown";
        String type = mime.trim().toLowerCase(Locale.ROOT);
        int slash = type.indexOf('/');
        String group = slash < 0 ? type : type.substring(0, slash);
        if ("application".equals(group) || "text".equals(group)) return "document";
        return group;
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? filename : filename.substring(dot + 1);
    }

    private static Map<String, String> extensionMime() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("jpg", "image/jpeg");
        map.put("jpeg", "image/jpeg");
        map.put("png", "image/png");
        map.put("gif", "image/gif");
        map.put("webp", "image/webp");
        map.put("svg", "image/svg+xml");
        map.put("mp4", "video/mp4");
        map.put("webm", "video/webm");
        map.put("mov", "video/quicktime");
        map.put("m4v", "video/x-m4v");
        // MPEG 传输流：Windows 上常被 MIME 探测误报为 text/plain，必须按扩展名归回视频
        map.put("ts", "video/mp2t");
        map.put("m2t", "video/mp2t");
        map.put("m2ts", "video/mp2t");
        map.put("mts", "video/mp2t");
        map.put("avi", "video/x-msvideo");
        map.put("mkv", "video/x-matroska");
        map.put("flv", "video/x-flv");
        map.put("wmv", "video/x-ms-wmv");
        map.put("3gp", "video/3gpp");
        map.put("mp3", "audio/mpeg");
        map.put("wav", "audio/x-wav");
        map.put("aac", "audio/aac");
        map.put("ogg", "audio/ogg");
        map.put("flac", "audio/flac");
        map.put("m4a", "audio/mp4");
        map.put("pdf", "application/pdf");
        map.put("rtf", "application/rtf");
        // 字体：主题/片段模板常需自托管 Web 字体（FR-CM-MEDIA-040 白名单）。
        // ttf/woff 在 Windows/浏览器常被探测成 application/octet-stream 或 text/plain，
        // 必须按扩展名归回字体组，否则会落到"扩展名未知 + 占位 MIME"分支被拒。
        // 仅收录 IANA 注册为 font/* 的格式（eot 无 font/* 型，不入表）。
        map.put("ttf", "font/ttf");
        map.put("otf", "font/otf");
        map.put("woff", "font/woff");
        map.put("woff2", "font/woff2");
        map.put("ttc", "font/collection");
        map.put("txt", "text/plain");
        map.put("md", "text/markdown");
        map.put("doc", "application/msword");
        map.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        map.put("xls", "application/vnd.ms-excel");
        map.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        map.put("ppt", "application/vnd.ms-powerpoint");
        map.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        map.put("zip", "application/zip");
        map.put("mpg", "video/mpeg");
        map.put("mpeg", "video/mpeg");
        map.put("m3u8", "application/vnd.apple.mpegurl");
        return Map.copyOf(map);
    }

    private record Candidate(ContentTypeEntity type, int level, int acceptCount) {}
}
