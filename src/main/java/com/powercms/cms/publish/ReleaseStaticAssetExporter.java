package com.powercms.cms.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.publish.entity.ReleaseMediaVersionEntity;
import com.powercms.cms.publish.entity.ReleaseUrlRewriteEntity;
import com.powercms.cms.publish.repo.ReleaseMediaVersionRepository;
import com.powercms.cms.publish.repo.ReleaseUrlRewriteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exports freeze-time media and rewrite snapshots into a static artifact tree.
 * Never reads current media/rewrite tables.
 */
@Service
public class ReleaseStaticAssetExporter {
    private static final Pattern EXACT = Pattern.compile("^\\^\\\\Q(.*?)\\\\E\\$$");

    private final ReleaseMediaVersionRepository mediaRepo;
    private final ReleaseUrlRewriteRepository rewriteRepo;
    private final ObjectMapper mapper;
    private final Path storageRoot;
    private final boolean failOnMissing;
    private final Object knoxSource; // optional Knox snapshot source (set via setter to avoid hard dep)

    ReleaseStaticAssetExporter(ReleaseMediaVersionRepository mediaRepo,
                               ReleaseUrlRewriteRepository rewriteRepo,
                               ObjectMapper mapper,
                               @Value("${powercms.media.storage-root:${java.io.tmpdir}/powercms-media}") String storageRoot,
                               @Value("${powercms.build.fail-on-missing-assets:false}") boolean failOnMissing) {
        this.mediaRepo = mediaRepo;
        this.rewriteRepo = rewriteRepo;
        this.mapper = mapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.failOnMissing = failOnMissing;
        this.knoxSource = null;
    }

    /** Knox 快照源（可选依赖，publish 包不直接 import knox 类型）。 */
    public interface KnxDownload {
        Long download(UUID assetId, String storageKey, Path target) throws Exception;

        PlaybackEntry playbackEntry(UUID siteId, UUID assetId, Path outputRoot);

        record PlaybackEntry(String assetId, String posterOutputPath, String playUrlTemplate,
                             Integer durationMs, Integer width, Integer height) {}

        static boolean isKnxSource(String storageKey) {
            return storageKey != null && storageKey.toLowerCase().startsWith("knx://");
        }
    }

    public ReleaseStaticAssetExporter withKnxDownload(KnxDownload download) {
        // 便捷装配入口：由配置类调用生成携带 knx 源的实例（保持构造器兼容）
        try {
            var clone = getClass().getDeclaredConstructor(
                    ReleaseMediaVersionRepository.class, ReleaseUrlRewriteRepository.class,
                    ObjectMapper.class, String.class, boolean.class)
                .newInstance(mediaRepo, rewriteRepo, mapper, storageRoot.toString(), failOnMissing);
            var field = getClass().getDeclaredField("knoxSource");
            field.setAccessible(true);
            field.set(clone, download);
            return (ReleaseStaticAssetExporter) clone;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private KnxDownload knx() {
        return knoxSource == null ? null : (KnxDownload) knoxSource;
    }

    public record ExportResult(int mediaCopied, int mediaMissing, int redirectsExported, int redirectsSkipped,
                               int playbackEntries) {}

    public ExportResult export(UUID releaseId, Path outputRoot) {
        return export(releaseId, outputRoot, null);
    }

    public ExportResult export(UUID releaseId, Path outputRoot, UUID siteId) {
        try {
            int copied = 0, missing = 0, playbackCount = 0;
            KnxDownload knx = knx();
            List<Map<String, Object>> mediaManifest = new ArrayList<>();
            List<KnxDownload.PlaybackEntry> playback = new ArrayList<>();
            for (ReleaseMediaVersionEntity media : mediaRepo.findByReleaseIdOrderByOutputPathAsc(releaseId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("assetId", media.getMediaAssetId().toString());
                row.put("version", media.getMediaVersion());
                row.put("outputPath", media.getOutputPath());
                row.put("contentHash", media.getContentHash());
                row.put("storageKey", media.getStorageKey());
                boolean avAsset = media.getMimeType() != null
                    && (media.getMimeType().startsWith("video/") || media.getMimeType().startsWith("audio/"));
                if (knx != null && KnxDownload.isKnxSource(media.getStorageKey())) {
                    if (avAsset && siteId != null) {
                        // 音视频类：不复制大文件，仅 playback manifest（FR-KNX-041）
                        var entry = knx.playbackEntry(siteId, media.getMediaAssetId(), outputRoot);
                        if (entry != null) {
                            playback.add(new KnxDownload.PlaybackEntry(entry.assetId(), entry.posterOutputPath(),
                                entry.playUrlTemplate(), entry.durationMs(), entry.width(), entry.height()));
                            playbackCount++;
                        }
                        row.put("status", "PLAYBACK");
                        copied++;
                    } else {
                        try {
                            Long size = knx.download(media.getMediaAssetId(), media.getStorageKey(),
                                BuildWorker.safe(outputRoot, media.getOutputPath()));
                            if (size == null) throw new IllegalStateException("knox source unavailable");
                            row.put("status", "COPIED");
                            row.put("bytes", size);
                            copied++;
                        } catch (Exception ex) {
                            row.put("status", "MISSING");
                            row.put("reason", ex.getMessage());
                            missing++;
                            if (failOnMissing) throw new IllegalStateException(
                                "media asset missing/unsafe: " + media.getOutputPath()
                                    + " (" + ex.getMessage() + ")", ex);
                        }
                    }
                } else {
                    try {
                        Path source = resolveStorage(media.getStorageKey());
                        Path target = BuildWorker.safe(outputRoot, media.getOutputPath());
                        Files.createDirectories(target.getParent());
                        copyVerified(source, target, media.getFileSize(), media.getContentHash());
                        row.put("status", "COPIED");
                        copied++;
                    } catch (Exception ex) {
                        row.put("status", "MISSING");
                        row.put("reason", ex.getMessage());
                        missing++;
                        if (failOnMissing) throw new IllegalStateException("media asset missing/unsafe: " + media.getOutputPath() + " (" + ex.getMessage() + ")", ex);
                    }
                }
                mediaManifest.add(row);
            }
            if (!playback.isEmpty()) {
                Map<String, Object> playbackSection = new LinkedHashMap<>();
                playbackSection.put("entries", playback);
                mediaManifest.add(Map.of("playback", playbackSection));
            }
            Files.writeString(outputRoot.resolve("media-manifest.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mediaManifest), StandardCharsets.UTF_8);

            List<Map<String, Object>> rewriteManifest = new ArrayList<>();
            StringBuilder redirects = new StringBuilder();
            StringBuilder nginx = new StringBuilder();
            int exported = 0, skipped = 0;
            for (ReleaseUrlRewriteEntity rule : rewriteRepo.findByReleaseIdOrderByPriorityDescNameAsc(releaseId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ruleId", rule.getRuleId().toString());
                row.put("name", rule.getName());
                row.put("pattern", rule.getPattern());
                row.put("replacement", rule.getReplacement());
                row.put("priority", rule.getPriority());
                Matcher m = EXACT.matcher(rule.getPattern());
                if (m.matches()) {
                    String from = m.group(1);
                    String to = rule.getReplacement();
                    redirects.append(from).append(' ').append(to).append(" 301\n");
                    nginx.append("location = ").append(from).append(" { return 301 ").append(to).append("; }\n");
                    row.put("exported", true);
                    exported++;
                } else {
                    row.put("exported", false);
                    row.put("reason", "non-exact regex");
                    skipped++;
                }
                rewriteManifest.add(row);
            }
            Files.writeString(outputRoot.resolve("_redirects"), redirects.toString(), StandardCharsets.UTF_8);
            Files.writeString(outputRoot.resolve("nginx.redirects"), nginx.toString(), StandardCharsets.UTF_8);
            Files.writeString(outputRoot.resolve("redirects-manifest.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rewriteManifest), StandardCharsets.UTF_8);
            return new ExportResult(copied, missing, exported, skipped, playbackCount);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static String safeFilename(String filename) {
        String name = filename == null ? "file" : filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank() || name.equals(".") || name.equals("..")) name = "file";
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    static String outputPath(UUID assetId, String filename) {
        return "media/" + assetId + "/" + safeFilename(filename);
    }

    Path resolveStorage(String storageKey) throws IOException {
        if (storageKey == null || storageKey.isBlank()) throw new IllegalArgumentException("blank storageKey");
        String key = storageKey.trim();
        Path resolved;
        if (key.regionMatches(true, 0, "file:", 0, 5)) {
            resolved = Path.of(URI.create(key)).toAbsolutePath().normalize();
        } else if (key.matches("^[A-Za-z]:[\\\\/].*") || key.startsWith("/") || key.startsWith("\\")) {
            throw new SecurityException("absolute storageKey rejected: " + key);
        } else {
            resolved = BuildWorker.safe(storageRoot, key.replace('\\', '/'));
        }
        if (Files.isSymbolicLink(resolved)) throw new SecurityException("symlink rejected");
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("not a regular file");
        return resolved;
    }

    private static void copyVerified(Path source, Path target, long expectedSize, String contentHash) throws Exception {
        long size = Files.size(source);
        if (expectedSize > 0 && size != expectedSize) throw new IllegalStateException("size mismatch expected=" + expectedSize + " actual=" + size);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        if (contentHash != null && contentHash.matches("(?i)[0-9a-f]{64}")) {
            String actual = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
            if (!actual.equalsIgnoreCase(contentHash)) throw new IllegalStateException("hash mismatch");
        }
    }
}
