package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.sitepackage.entity.SiteThemeEntity;
import com.powercms.cms.sitepackage.entity.SiteThemeFileEntity;
import com.powercms.cms.sitepackage.entity.SiteThemeRevisionEntity;
import com.powercms.cms.sitepackage.entity.SiteThemeRevisionFileEntity;
import com.powercms.cms.sitepackage.entity.SiteThemeShareEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitepackage.repo.SiteThemeFileRepository;
import com.powercms.cms.sitepackage.repo.SiteThemeRepository;
import com.powercms.cms.sitepackage.repo.SiteThemeRevisionRepository;
import com.powercms.cms.sitepackage.repo.SiteThemeRevisionFileRepository;
import com.powercms.cms.sitepackage.repo.SiteThemeShareRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

@Service
@Transactional
public class ThemePackageService {
    static final long MAX_ARCHIVE_BYTES = 25L * 1024 * 1024;
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 50L * 1024 * 1024;
    static final int MAX_FILES = 500;
    private static final String CMS_VERSION = "12.0.0";
    private static final int MAX_INHERITANCE_DEPTH = 16;
    private static final Set<String> REQUIRED_FRAGMENTS = Set.of("html_head.ftl", "header.ftl", "footer.ftl");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]");
    private static final Pattern DANGEROUS = Pattern.compile("(?is)(Runtime\\.getRuntime|ProcessBuilder|child_process|os\\.system|subprocess\\.|powershell\\s+-enc|curl\\s+[^\\r\\n]*\\|\\s*(?:sh|bash))");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");

    private final SiteThemeRepository themeRepo;
    private final SiteThemeFileRepository fileRepo;
    private final SiteThemeRevisionRepository revisionRepo;
    private final SiteThemeRevisionFileRepository revisionFileRepo;
    private final SiteThemeShareRepository shareRepo;
    private final SiteRepository siteRepo;
    private final com.powercms.cms.publish.repo.ReleaseThemeVersionRepository releaseThemeRepo;
    private final AuditService audit;
    private final RenderDatasetCache cache;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json;

    ThemePackageService(SiteThemeRepository themeRepo, SiteThemeFileRepository fileRepo,
                        SiteThemeRevisionRepository revisionRepo, SiteThemeRevisionFileRepository revisionFileRepo,
                        SiteThemeShareRepository shareRepo, SiteRepository siteRepo,
                        com.powercms.cms.publish.repo.ReleaseThemeVersionRepository releaseThemeRepo,
                        AuditService audit, RenderDatasetCache cache, ObjectMapper json) {
        this.themeRepo = themeRepo; this.fileRepo = fileRepo; this.revisionRepo = revisionRepo; this.revisionFileRepo = revisionFileRepo; this.shareRepo = shareRepo; this.siteRepo = siteRepo; this.releaseThemeRepo = releaseThemeRepo; this.audit = audit; this.cache = cache; this.json = json;
    }

    public record FileDigest(String path, String mediaType, long size, String sha256) {}
    public record BrowserFile(String path, String mediaType, long size, String sha256, String content, boolean editable, int version) {}
    public record Manifest(String name, String version, String vendor, String cmsVersionRange) {}
    public record ImportResult(UUID themeId, int version, String validity, Manifest manifest, List<String> warnings,
                               List<FileDigest> files) {}
    public record ExportResult(String fileName, byte[] content, List<FileDigest> files) {}
    public record RevisionInfo(UUID id, UUID themeId, int version, Integer parentVersion, UUID parentThemeId, Integer parentThemeVersion, String author,
                               String message, String manifestSnapshot, String fileDigest, java.time.Instant createdAt) {}
    public record RevisionDetail(RevisionInfo revision, Manifest manifest, List<FileDigest> files) {}
    public record DiffEntry(String path, String change, String fromSha256, String toSha256) {}
    public record ShareInfo(UUID id, UUID themeId, UUID consumerSiteId, int pinnedVersion, String status,
                            String createdBy, java.time.Instant createdAt, java.time.Instant updatedAt) {}
    public record AvailableTheme(SiteThemeEntity theme, String source, UUID ownerSiteId, boolean readOnly,
                                 Integer pinnedVersion) {}

    @Transactional(readOnly = true)
    public List<ShareInfo> shares(UUID ownerSiteId, UUID themeId) {
        requireTheme(ownerSiteId, themeId);
        return shareRepo.findByThemeIdOrderByCreatedAtAsc(themeId).stream().map(this::shareInfo).toList();
    }

    public ShareInfo grantShare(UUID ownerSiteId, UUID themeId, UUID consumerSiteId, Integer version, String actor) {
        SiteThemeEntity theme = requireShareableOwnerTheme(ownerSiteId, themeId);
        if (consumerSiteId == null || !siteRepo.existsById(consumerSiteId)) throw invalid("消费站点不存在");
        if (ownerSiteId.equals(consumerSiteId)) throw invalid("主题不能共享给所属站点自身");
        int pinned = version == null ? theme.getCurrentVersion() : version;
        requireRevision(themeId, pinned);
        SiteThemeShareEntity share = shareRepo.findByThemeIdAndConsumerSiteId(themeId, consumerSiteId).orElseGet(SiteThemeShareEntity::new);
        share.setThemeId(themeId); share.setConsumerSiteId(consumerSiteId); share.setPinnedVersion(pinned);
        share.setStatus(SiteThemeShareEntity.ACTIVE); if (share.getCreatedBy() == null) share.setCreatedBy(actor);
        share = shareRepo.save(share);
        audit.write(actor, "THEME_SHARE_GRANT", "THEME", themeId.toString(), ownerSiteId, "SUCCESS", "consumerSiteId="+consumerSiteId+"; pinnedVersion="+pinned);
        return shareInfo(share);
    }

    public ShareInfo updateShareVersion(UUID ownerSiteId, UUID themeId, UUID consumerSiteId, int version, String actor) {
        requireShareableOwnerTheme(ownerSiteId, themeId); requireRevision(themeId, version);
        SiteThemeShareEntity share = requireShare(themeId, consumerSiteId);
        share.setPinnedVersion(version); share.setStatus(SiteThemeShareEntity.ACTIVE); share = shareRepo.save(share);
        audit.write(actor, "THEME_SHARE_VERSION_UPDATE", "THEME", themeId.toString(), ownerSiteId, "SUCCESS", "consumerSiteId="+consumerSiteId+"; pinnedVersion="+version);
        return shareInfo(share);
    }

    public void revokeShare(UUID ownerSiteId, UUID themeId, UUID consumerSiteId, String actor) {
        requireTheme(ownerSiteId, themeId); SiteThemeShareEntity share = requireShare(themeId, consumerSiteId);
        share.setStatus(SiteThemeShareEntity.REVOKED); shareRepo.save(share);
        audit.write(actor, "THEME_SHARE_REVOKE", "THEME", themeId.toString(), ownerSiteId, "SUCCESS", "consumerSiteId="+consumerSiteId);
    }

    @Transactional(readOnly = true)
    public List<AvailableTheme> themesAvailableToSite(UUID siteId) {
        return themesAvailableToSite(siteId, false);
    }

    @Transactional(readOnly = true)
    public List<AvailableTheme> themesAvailableToSite(UUID siteId, boolean includeUnrecognized) {
        List<AvailableTheme> result = new ArrayList<>();
        themeRepo.findBySiteIdOrderByNameAsc(siteId).forEach(t -> result.add(new AvailableTheme(t, "OWNED", siteId, false, null)));
        for (SiteThemeShareEntity share : shareRepo.findByConsumerSiteIdAndStatus(siteId, SiteThemeShareEntity.ACTIVE)) {
            themeRepo.findById(share.getThemeId()).filter(t -> "VALID".equals(t.getValidity()))
                .ifPresent(t -> result.add(new AvailableTheme(t, "SHARED", t.getSiteId(), true, share.getPinnedVersion())));
        }
        if (!includeUnrecognized) result.removeIf(theme -> !isRecognizedTheme(theme));
        result.sort(Comparator.comparing(a -> a.theme().getName(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }
    private boolean isRecognizedTheme(AvailableTheme available) {
        SiteThemeEntity theme = available.theme();
        if (!"PUBLISHED".equals(theme.getStatus()) || !"VALID".equals(theme.getValidity())) return false;
        String requiredPath = "themes/" + theme.getName() + "/template.ftl";
        if (!available.readOnly()) {
            return fileRepo.findByThemeIdAndPath(theme.getId(), requiredPath)
                .map(file -> file.getContent() != null && file.getContent().length > 0)
                .orElse(false);
        }
        SiteThemeRevisionEntity revision = revisionRepo.findByThemeIdAndVersion(theme.getId(), available.pinnedVersion()).orElse(null);
        if (revision == null) return false;
        return revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(revision.getId()).stream()
            .anyMatch(file -> requiredPath.equals(file.getPath()) && file.getContent() != null && file.getContent().length > 0);
    }

    @Transactional(readOnly = true)
    public AvailableTheme resolveAvailableTheme(UUID consumerSiteId, UUID themeId) {
        SiteThemeEntity theme = themeRepo.findById(themeId).orElseThrow(() -> invalid("主题不存在或未授权给当前站点"));
        if (consumerSiteId.equals(theme.getSiteId())) return new AvailableTheme(theme, "OWNED", theme.getSiteId(), false, null);
        SiteThemeShareEntity share = shareRepo.findByThemeIdAndConsumerSiteId(themeId, consumerSiteId)
            .filter(x -> SiteThemeShareEntity.ACTIVE.equals(x.getStatus())).orElseThrow(() -> invalid("主题不存在、共享已撤销或未授权给当前站点"));
        requireRevision(themeId, share.getPinnedVersion());
        return new AvailableTheme(theme, "SHARED", theme.getSiteId(), true, share.getPinnedVersion());
    }

    @Transactional(readOnly = true)
    public boolean isRenderableAvailable(UUID consumerSiteId, UUID themeId) {
        AvailableTheme available = resolveAvailableTheme(consumerSiteId, themeId);
        if (!"VALID".equals(available.theme().getValidity())) return false;
        return readAvailableText(consumerSiteId, themeId, "template.ftl", false) != null;
    }

    @Transactional(readOnly = true)
    public String readAvailableText(UUID consumerSiteId, UUID themeId, String relativePath, boolean useCache) {
        AvailableTheme available = resolveAvailableTheme(consumerSiteId, themeId);
        if (!"VALID".equals(available.theme().getValidity())) throw invalid("主题不兼容或无有效主题包，无法渲染");
        if (!available.readOnly()) return readText(consumerSiteId, themeId, relativePath, useCache);
        int version = available.pinnedVersion();
        SiteThemeRevisionEntity revision = requireRevision(themeId, version);
        String key = consumerSiteId + ":shared:" + themeId + ":v" + version + ":" + relativePath;
        return cache.getOrLoad(RenderDatasetCache.Region.THEME, key, useCache,
            () -> resolveRevision(available.ownerSiteId(), revision, relativePath, new HashSet<>(), 0));
    }

    @Transactional(readOnly = true)
    public List<BrowserFile> browserFiles(UUID siteId, UUID themeId) {
        AvailableTheme available = resolveAvailableTheme(siteId, themeId);
        if (available.readOnly()) {
            SiteThemeRevisionEntity revision = requireRevision(themeId, available.pinnedVersion());
            return revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(revision.getId()).stream()
                .map(file -> browserFile(file.getPath(), file.getMediaType(), file.getSize(), file.getSha256(), file.getContent(), false, available.pinnedVersion())).toList();
        }
        return fileRepo.findByThemeIdOrderByPathAsc(themeId).stream()
            .map(file -> browserFile(file.getPath(), file.getMediaType(), file.getSize(), file.getSha256(), file.getContent(), true, available.theme().getCurrentVersion())).toList();
    }

    /**
     * Relative theme paths under {@code css/} or {@code js/} that Site Browser and the renderer both see.
     * Only lists files stored on the theme (or pinned shared revision), matching browser visibility.
     */
    @Transactional(readOnly = true)
    public List<String> listRelativeTextAssets(UUID siteId, UUID themeId, String directory) {
        if (!"css".equals(directory) && !"js".equals(directory)) throw invalid("仅支持列出 css 或 js 主题资源");
        AvailableTheme available = resolveAvailableTheme(siteId, themeId);
        String root = "themes/" + available.theme().getName() + "/";
        String prefix = root + directory + "/";
        List<String> fullPaths = available.readOnly()
            ? revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(requireRevision(themeId, available.pinnedVersion()).getId())
                .stream().map(SiteThemeRevisionFileEntity::getPath).toList()
            : fileRepo.findByThemeIdOrderByPathAsc(themeId).stream().map(SiteThemeFileEntity::getPath).toList();
        return fullPaths.stream()
            .filter(path -> path.startsWith(prefix))
            .map(path -> path.substring(root.length()))
            .filter(relative -> isTextMedia(mediaType(relative)))
            .sorted()
            .toList();
    }

    @Transactional(readOnly = true)
    public List<String> listRevisionRelativeTextAssets(UUID themeId, UUID revisionId, String directory) {
        if (!"css".equals(directory) && !"js".equals(directory)) throw invalid("仅支持列出 css 或 js 主题资源");
        SiteThemeEntity theme = themeRepo.findById(themeId).orElseThrow(() -> invalid("主题不存在"));
        String root = "themes/" + theme.getName() + "/";
        String prefix = root + directory + "/";
        return revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(revisionId).stream()
            .map(SiteThemeRevisionFileEntity::getPath)
            .filter(path -> path.startsWith(prefix))
            .map(path -> path.substring(root.length()))
            .filter(relative -> isTextMedia(mediaType(relative)))
            .sorted()
            .toList();
    }

    public BrowserFile saveBrowserTextFile(UUID siteId, UUID themeId, String path, String content, int expectedVersion, String actor) {
        SiteThemeEntity theme = requireTheme(siteId, themeId);
        if (theme.getCurrentVersion() != expectedVersion) throw conflict();
        String safePath = validatePath(path);
        String root = "themes/" + theme.getName() + "/";
        if (!safePath.startsWith(root) || safePath.length() == root.length()) throw invalid("文件路径必须在主题目录内: " + root);
        if (safePath.equals(root + "theme.yaml")) throw invalid("theme.yaml 为受保护清单文件，不可在浏览器中编辑");
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw invalid("单文件不能超过 10 MiB");
        if (!isTextMedia(mediaType(safePath))) throw invalid("浏览器编辑仅支持文本文件（文本/JSON/JS/YAML 等）");
        scan(safePath, bytes);
        LinkedHashMap<String, byte[]> snapshot = new LinkedHashMap<>();
        fileRepo.findByThemeIdOrderByPathAsc(themeId).forEach(file -> snapshot.put(file.getPath(), file.getContent()));
        snapshot.put(safePath, bytes);
        int parent = theme.getCurrentVersion(), next = parent + 1;
        String requiredPath = root + "template.ftl";
        boolean recognized = snapshot.containsKey(requiredPath) && snapshot.get(requiredPath).length > 0;
        theme.setCurrentVersion(next);
        if (recognized) {
            theme.setStatus("PUBLISHED"); theme.setValidity("VALID");
            if (theme.getManifestVersion() == null) theme.setManifestVersion("1.0.0");
            if (theme.getVendor() == null) theme.setVendor("PowerCMS");
            if (theme.getCmsVersionRange() == null) theme.setCmsVersionRange("12.x");
        }
        try { themeRepo.saveAndFlush(theme); } catch (ObjectOptimisticLockingFailureException ex) { throw conflict(); }
        replaceLiveFiles(themeId, snapshot);
        SiteThemeRevisionEntity previous = parent > 0 ? requireRevision(themeId, parent) : null;
        SiteThemeRevisionEntity revision = new SiteThemeRevisionEntity(); revision.setThemeId(themeId); revision.setVersion(next); revision.setParentVersion(parent == 0 ? null : parent); revision.setParentThemeId(theme.getParentThemeId()); revision.setParentThemeVersion(theme.getParentVersion());
        revision.setAuthor(actor); revision.setMessage("编辑主题文件 " + safePath);
        revision.setManifestSnapshot(previous == null ? write(new Manifest(theme.getName(), "1.0.0", "PowerCMS", "12.x")) : previous.getManifestSnapshot());
        revision.setFileDigest(snapshotDigest(snapshot)); revision = revisionRepo.save(revision); saveSnapshot(revision.getId(), snapshot);
        cache.invalidatePrefix(RenderDatasetCache.Region.THEME, themeId + ":");
        audit.write(actor, "THEME_FILE_EDIT", "THEME", themeId.toString(), siteId, "SUCCESS", "path=" + safePath + "; version=" + next);
        return browserFile(safePath, mediaType(safePath), bytes.length, sha256(bytes), bytes, true, next);
    }

    private BrowserFile browserFile(String path, String mediaType, long size, String sha, byte[] content, boolean editable, int version) {
        String text = isTextMedia(mediaType) && content.length <= 2 * 1024 * 1024 ? new String(content, StandardCharsets.UTF_8) : null;
        return new BrowserFile(path, mediaType, size, sha, text, editable && text != null, version);
    }
    private static boolean isTextMedia(String mediaType) { return mediaType.startsWith("text/") || mediaType.contains("json") || mediaType.contains("javascript") || mediaType.contains("xml") || mediaType.contains("yaml"); }

    public ImportResult importZip(UUID siteId, byte[] archive, String actor) { return importZip(siteId, archive, actor, null); }

    public ImportResult importZip(UUID siteId, byte[] archive, String actor, String message) {
        if (archive == null || archive.length == 0) throw invalid("主题 ZIP 不能为空");
        if (archive.length > MAX_ARCHIVE_BYTES) throw invalid("主题 ZIP 超过 25 MiB 限制");
        LinkedHashMap<String, byte[]> raw = readZip(archive);
        String manifestPath = raw.keySet().stream().filter(p -> p.equals("theme.yaml") || p.matches("themes/[^/]+/theme\\.yaml"))
            .findFirst().orElseThrow(() -> invalid("ZIP 必须包含 theme.yaml"));
        Manifest manifest = parseManifest(raw.get(manifestPath));
        validateThemeName(manifest.name());
        String prefix = manifestPath.equals("theme.yaml") ? "" : manifestPath.substring(0, manifestPath.length() - "theme.yaml".length());
        if (!prefix.isEmpty() && !prefix.equals("themes/" + manifest.name() + "/"))
            throw invalid("theme.yaml 路径与清单 name 不一致，必须为 themes/" + manifest.name() + "/theme.yaml");
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            String relative;
            if (prefix.isEmpty()) relative = entry.getKey();
            else if (entry.getKey().startsWith(prefix)) relative = entry.getKey().substring(prefix.length());
            else throw invalid("ZIP 条目不在主题标准路径内: " + entry.getKey());
            if (relative.isBlank() || relative.startsWith("themes/")) throw invalid("ZIP 含无效主题路径: " + entry.getKey());
            files.put("themes/" + manifest.name() + "/" + relative, entry.getValue());
        }
        String root = "themes/" + manifest.name() + "/";
        if (!files.containsKey(root + "template.ftl")) throw invalid("主题缺少必需文件 template.ftl");
        List<String> warnings = new ArrayList<>();
        for (String fragment : REQUIRED_FRAGMENTS) if (!files.containsKey(root + fragment)) warnings.add("缺少可选片段 " + fragment + "，渲染时将使用空内容");
        boolean compatible = compatible(manifest.cmsVersionRange());
        String validity = compatible ? "VALID" : "INCOMPATIBLE";
        if (!compatible) warnings.add("主题要求 CMS " + manifest.cmsVersionRange() + "，当前版本为 " + CMS_VERSION + "；已导入但不可绑定或激活");

        SiteThemeEntity theme = themeRepo.findBySiteIdAndNameIgnoreCase(siteId, manifest.name()).orElseGet(SiteThemeEntity::new);
        theme.setSiteId(siteId); theme.setName(manifest.name()); theme.setManifestVersion(manifest.version());
        theme.setVendor(manifest.vendor()); theme.setCmsVersionRange(manifest.cmsVersionRange()); theme.setValidity(validity);
        theme.setWarnings(write(warnings)); theme.setStatus(compatible ? "PUBLISHED" : "DRAFT");
        theme.setDescription("Imported theme package by " + manifest.vendor());
        int previousVersion = theme.getCurrentVersion();
        int version = previousVersion + 1;
        theme.setCurrentVersion(version);
        try { theme = themeRepo.saveAndFlush(theme); }
        catch (ObjectOptimisticLockingFailureException ex) { throw conflict(); }
        replaceLiveFiles(theme.getId(), files);
        SiteThemeRevisionEntity revision = new SiteThemeRevisionEntity();
        revision.setThemeId(theme.getId()); revision.setVersion(version); revision.setParentVersion(previousVersion == 0 ? null : previousVersion); revision.setParentThemeId(theme.getParentThemeId()); revision.setParentThemeVersion(theme.getParentVersion());
        revision.setAuthor(actor); revision.setMessage(message == null || message.isBlank() ? "导入主题包 " + version : message.trim());
        revision.setManifestSnapshot(write(manifest)); revision.setFileDigest(snapshotDigest(files));
        revision = revisionRepo.save(revision);
        saveSnapshot(revision.getId(), files);
        if (theme.getParentThemeId() != null) {
            UUID importedThemeId = theme.getId();
            warnings.removeIf(warning -> REQUIRED_FRAGMENTS.stream().anyMatch(fragment ->
                warning.contains(fragment) && readText(siteId, importedThemeId, fragment, false) != null));
            theme.setWarnings(write(warnings)); themeRepo.save(theme);
        }
        List<FileDigest> digests = digestList(files);
        cache.invalidatePrefix(RenderDatasetCache.Region.THEME, theme.getId() + ":");
        audit.write(actor, "THEME_IMPORT", "THEME", theme.getId().toString(), siteId, "SUCCESS",
            "name=" + manifest.name() + "; version=" + version + "; validity=" + validity + "; files=" + files.size());
        return new ImportResult(theme.getId(), version, validity, manifest, List.copyOf(warnings), digests);
    }

    @Transactional(readOnly = true)
    public ExportResult exportZip(UUID siteId, UUID themeId, String actor) { return exportZip(siteId, themeId, null, actor); }

    @Transactional(readOnly = true)
    public ExportResult exportZip(UUID siteId, UUID themeId, Integer version, String actor) {
        SiteThemeEntity theme = requireTheme(siteId, themeId);
        int selectedVersion = version == null ? theme.getCurrentVersion() : version;
        SiteThemeRevisionEntity revision = requireRevision(themeId, selectedVersion);
        List<SiteThemeRevisionFileEntity> files = revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(revision.getId());
        if (files.isEmpty()) throw invalid("主题版本没有可导出的包文件");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (SiteThemeRevisionFileEntity file : files) { ZipEntry entry = new ZipEntry(file.getPath()); entry.setTime(0); zip.putNextEntry(entry); zip.write(file.getContent()); zip.closeEntry(); }
            }
            List<FileDigest> digests = files.stream().map(f -> new FileDigest(f.getPath(), f.getMediaType(), f.getSize(), f.getSha256())).toList();
            audit.write(actor, "THEME_EXPORT", "THEME", themeId.toString(), siteId, "SUCCESS", "version=" + selectedVersion + "; files=" + files.size());
            return new ExportResult(theme.getName() + "-v" + selectedVersion + ".zip", bytes.toByteArray(), digests);
        } catch (IOException ex) { throw invalid("主题 ZIP 导出失败"); }
    }

    @Transactional(readOnly = true)
    public List<RevisionInfo> revisions(UUID siteId, UUID themeId) {
        requireTheme(siteId, themeId);
        return revisionRepo.findByThemeIdOrderByVersionDesc(themeId).stream().map(this::revisionInfo).toList();
    }

    @Transactional(readOnly = true)
    public RevisionDetail revision(UUID siteId, UUID themeId, int version) {
        requireTheme(siteId, themeId); SiteThemeRevisionEntity r = requireRevision(themeId, version);
        List<FileDigest> files = revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(r.getId()).stream()
            .map(f -> new FileDigest(f.getPath(), f.getMediaType(), f.getSize(), f.getSha256())).toList();
        return new RevisionDetail(revisionInfo(r), readManifest(r.getManifestSnapshot()), files);
    }

    @Transactional(readOnly = true)
    public List<DiffEntry> diff(UUID siteId, UUID themeId, int v1, int v2) {
        requireTheme(siteId, themeId);
        Map<String,String> a = snapshotHashes(requireRevision(themeId, v1));
        Map<String,String> b = snapshotHashes(requireRevision(themeId, v2));
        TreeSet<String> paths = new TreeSet<>(); paths.addAll(a.keySet()); paths.addAll(b.keySet());
        List<DiffEntry> result = new ArrayList<>();
        for (String path : paths) {
            String from=a.get(path), to=b.get(path); if (Objects.equals(from,to)) continue;
            result.add(new DiffEntry(path, from == null ? "ADDED" : to == null ? "DELETED" : "MODIFIED", from, to));
        }
        return List.copyOf(result);
    }

    public ImportResult restore(UUID siteId, UUID themeId, int version, String actor, String message) {
        SiteThemeEntity theme = requireTheme(siteId, themeId); SiteThemeRevisionEntity source = requireRevision(themeId, version);
        Manifest manifest = readManifest(source.getManifestSnapshot());
        LinkedHashMap<String,byte[]> snapshot = new LinkedHashMap<>();
        for (SiteThemeRevisionFileEntity f : revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(source.getId())) snapshot.put(f.getPath(), f.getContent());
        int parent=theme.getCurrentVersion(), next=parent+1;
        boolean compatible = compatible(manifest.cmsVersionRange());
        List<String> warnings = fragmentWarnings(snapshot, manifest, compatible);
        theme.setManifestVersion(manifest.version()); theme.setVendor(manifest.vendor()); theme.setCmsVersionRange(manifest.cmsVersionRange());
        theme.setValidity(compatible ? "VALID" : "INCOMPATIBLE"); theme.setWarnings(write(warnings)); theme.setStatus(compatible ? "PUBLISHED" : "DRAFT"); theme.setCurrentVersion(next);
        theme.setParentThemeId(source.getParentThemeId()); theme.setParentVersion(source.getParentThemeId() == null ? null : source.getParentThemeVersion());
        try { themeRepo.saveAndFlush(theme); } catch (ObjectOptimisticLockingFailureException ex) { throw conflict(); }
        replaceLiveFiles(themeId, snapshot);
        SiteThemeRevisionEntity revision=new SiteThemeRevisionEntity(); revision.setThemeId(themeId); revision.setVersion(next); revision.setParentVersion(parent); revision.setParentThemeId(source.getParentThemeId()); revision.setParentThemeVersion(source.getParentThemeId() == null ? null : source.getParentThemeVersion());
        revision.setAuthor(actor); revision.setMessage(message == null || message.isBlank() ? "恢复主题版本 " + version : message.trim());
        revision.setManifestSnapshot(source.getManifestSnapshot()); revision.setFileDigest(source.getFileDigest()); revision=revisionRepo.save(revision); saveSnapshot(revision.getId(),snapshot);
        cache.invalidatePrefix(RenderDatasetCache.Region.THEME, themeId + ":");
        audit.write(actor,"THEME_RESTORE","THEME",themeId.toString(),siteId,"SUCCESS","sourceVersion="+version+"; version="+next);
        return new ImportResult(themeId,next,theme.getValidity(),manifest,List.copyOf(warnings),digestList(snapshot));
    }

    public void deleteRevision(UUID siteId, UUID themeId, int version, String actor) {
        SiteThemeEntity theme = requireTheme(siteId, themeId);
        SiteThemeRevisionEntity revision = requireRevision(themeId, version);
        String blocked = null;
        if (theme.getCurrentVersion() == version) blocked = "\u4e0d\u80fd\u5220\u9664\u4e3b\u9898\u5f53\u524d\u7248\u672c";
        else if (releaseThemeRepo.existsByThemeRevisionId(revision.getId())) blocked = "\u8be5\u4e3b\u9898 revision \u5df2\u88ab Release \u56fa\u5b9a\uff0c\u4e0d\u80fd\u5220\u9664";
        else if (shareRepo.existsByThemeIdAndPinnedVersionAndStatus(themeId, version, SiteThemeShareEntity.ACTIVE)) blocked = "\u8be5\u4e3b\u9898\u7248\u672c\u4ecd\u88ab\u6d3b\u52a8\u5171\u4eab\u56fa\u5b9a\uff0c\u4e0d\u80fd\u5220\u9664";
        else if (revisionRepo.existsByParentThemeIdAndParentThemeVersion(themeId, version)) blocked = "\u8be5\u4e3b\u9898\u7248\u672c\u4ecd\u88ab\u5b50\u4e3b\u9898 revision \u7ee7\u627f\uff0c\u4e0d\u80fd\u5220\u9664";
        if (blocked != null) {
            audit.write(actor, "THEME_REVISION_DELETE", "THEME_REVISION", themeId + "@" + version, siteId, "BLOCKED", blocked);
            throw new BusinessException(ErrorCodes.CONFLICT, blocked);
        }
        revisionRepo.delete(revision);
        cache.invalidatePrefix(RenderDatasetCache.Region.THEME, themeId + ":v" + version + ":");
        audit.write(actor, "THEME_REVISION_DELETE", "THEME_REVISION", themeId + "@" + version, siteId, "SUCCESS", "version=" + version);
    }

    @Transactional(readOnly = true)
    public boolean isRenderablePackage(UUID siteId, UUID themeId) {
        SiteThemeEntity theme = requireTheme(siteId, themeId);
        return "VALID".equals(theme.getValidity()) && readText(siteId, themeId, "template.ftl", false) != null;
    }

    public RevisionInfo setParent(UUID siteId, UUID themeId, UUID parentThemeId, String actor) {
        SiteThemeEntity theme = requireTheme(siteId, themeId);
        UUID fixedParentId = null; Integer fixedParentVersion = null;
        if (parentThemeId != null) {
            if (themeId.equals(parentThemeId)) throw invalid("\u4e3b\u9898\u4e0d\u80fd\u7ee7\u627f\u81ea\u8eab");
            SiteThemeEntity parent = requireTheme(siteId, parentThemeId);
            if (!"VALID".equals(parent.getValidity()) || parent.getCurrentVersion() <= 0 || revisionRepo.findByThemeIdAndVersion(parentThemeId, parent.getCurrentVersion()).isEmpty())
                throw invalid("\u7236\u4e3b\u9898\u5fc5\u987b\u4e3a\u540c\u7ad9\u70b9 VALID \u4e3b\u9898\u4e14\u5177\u6709\u6709\u6548 revision");
            ensureNoCycle(themeId, parent);
            fixedParentId = parentThemeId; fixedParentVersion = parent.getCurrentVersion();
        }
        int next = theme.getCurrentVersion() + 1;
        theme.setParentThemeId(fixedParentId); theme.setParentVersion(fixedParentVersion); theme.setCurrentVersion(next);
        try { themeRepo.saveAndFlush(theme); } catch (ObjectOptimisticLockingFailureException ex) { throw conflict(); }
        SiteThemeRevisionEntity previous = next > 1 ? requireRevision(themeId, next - 1) : null;
        LinkedHashMap<String,byte[]> snapshot = new LinkedHashMap<>();
        if (previous != null) for (SiteThemeRevisionFileEntity f : revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(previous.getId())) snapshot.put(f.getPath(), f.getContent());
        SiteThemeRevisionEntity revision = new SiteThemeRevisionEntity(); revision.setThemeId(themeId); revision.setVersion(next);
        revision.setParentVersion(next > 1 ? next - 1 : null); revision.setParentThemeId(fixedParentId); revision.setParentThemeVersion(fixedParentVersion); revision.setAuthor(actor);
        revision.setMessage(fixedParentId == null ? "\u6e05\u9664\u7236\u4e3b\u9898" : "\u8bbe\u7f6e\u7236\u4e3b\u9898 " + fixedParentId + "@" + fixedParentVersion);
        revision.setManifestSnapshot(previous == null ? write(new Manifest(theme.getName(), Objects.toString(theme.getManifestVersion(), ""), Objects.toString(theme.getVendor(), ""), Objects.toString(theme.getCmsVersionRange(), "*"))) : previous.getManifestSnapshot());
        revision.setFileDigest(previous == null ? snapshotDigest(snapshot) : previous.getFileDigest()); revision = revisionRepo.save(revision); saveSnapshot(revision.getId(), snapshot);
        cache.invalidatePrefix(RenderDatasetCache.Region.THEME, themeId + ":");
        audit.write(actor, "THEME_PARENT_UPDATE", "THEME", themeId.toString(), siteId, "SUCCESS", "parentThemeId=" + fixedParentId + "; parentVersion=" + fixedParentVersion + "; version=" + next);
        return revisionInfo(revision);
    }

    @Transactional(readOnly = true)
    public String readText(UUID siteId, UUID themeId, String relativePath, boolean useCache) {
        SiteThemeEntity theme = requireTheme(siteId, themeId);
        if (!"VALID".equals(theme.getValidity())) throw invalid("\u4e3b\u9898\u4e0d\u517c\u5bb9\u6216\u65e0\u6709\u6548\u4e3b\u9898\u5305\uff0c\u65e0\u6cd5\u6e32\u67d3");
        String chain = inheritanceCacheKey(theme, new HashSet<>(), 0);
        return cache.getOrLoad(RenderDatasetCache.Region.THEME, themeId + ":" + chain + ":" + relativePath, useCache,
            () -> resolveCurrent(siteId, theme, relativePath, new HashSet<>(), 0));
    }

    @Transactional(readOnly = true)
    public String readText(UUID siteId, UUID themeId, int version, String relativePath, boolean useCache) {
        requireTheme(siteId, themeId); SiteThemeRevisionEntity revision = requireRevision(themeId, version);
        return cache.getOrLoad(RenderDatasetCache.Region.THEME, themeId + ":v" + version + ":" + relativePath, useCache,
            () -> resolveRevision(siteId, revision, relativePath, new HashSet<>(), 0));
    }

    @Transactional(readOnly = true)
    public String readRevisionText(UUID themeId, UUID revisionId, String relativePath,
                                   com.powercms.cms.publish.ReleaseThemeContext context, boolean useCache) {
        SiteThemeRevisionEntity revision = revisionRepo.findById(revisionId)
            .filter(r -> themeId.equals(r.getThemeId()))
            .orElseThrow(() -> invalid("Release \u56fa\u5b9a\u7684\u4e3b\u9898 revision \u4e0d\u5b58\u5728\u6216\u4e0e\u4e3b\u9898\u4e0d\u5339\u914d"));
        String key = "release:" + revisionId + ":" + relativePath;
        return cache.getOrLoad(RenderDatasetCache.Region.THEME, key, useCache,
            () -> resolveReleaseRevision(revision, relativePath, context, new HashSet<>(), 0));
    }

    @Transactional(readOnly = true)
    public void validateReleaseRevision(UUID themeId, UUID revisionId,
                                        com.powercms.cms.publish.ReleaseThemeContext context) {
        SiteThemeRevisionEntity revision = revisionRepo.findById(revisionId)
            .filter(r -> themeId.equals(r.getThemeId()))
            .orElseThrow(() -> invalid("Release \u56fa\u5b9a\u7684\u4e3b\u9898 revision \u4e0d\u5b58\u5728\u6216\u4e0e\u4e3b\u9898\u4e0d\u5339\u914d"));
        Manifest manifest = readManifest(revision.getManifestSnapshot());
        if (!compatible(manifest.cmsVersionRange())) throw invalid("Release \u4e3b\u9898 revision \u4e0e\u5f53\u524d CMS \u7248\u672c\u4e0d\u517c\u5bb9: " + themeId + "@" + revision.getVersion());
        if (readRevisionText(themeId, revisionId, "template.ftl", context, false) == null)
            throw invalid("Release \u4e3b\u9898\u7ee7\u627f\u95ed\u5305\u7f3a\u5c11\u5fc5\u9700\u6587\u4ef6 template.ftl: " + themeId + "@" + revision.getVersion());
    }

    @Transactional(readOnly = true)
    public boolean hasFile(UUID siteId, UUID themeId, String relativePath) { return readText(siteId, themeId, relativePath, false) != null; }

    private String resolveCurrent(UUID siteId, SiteThemeEntity theme, String relativePath, Set<UUID> visited, int depth) {
        checkTraversal(theme.getId(), visited, depth);
        String path = "themes/" + theme.getName() + "/" + relativePath;
        Optional<SiteThemeFileEntity> own = fileRepo.findByThemeIdAndPath(theme.getId(), path);
        if (own.isPresent()) return new String(own.get().getContent(), StandardCharsets.UTF_8);
        if (theme.getParentThemeId() == null || theme.getParentVersion() == null) return null;
        SiteThemeEntity parent = requireTheme(siteId, theme.getParentThemeId());
        return resolveRevision(siteId, requireRevision(parent.getId(), theme.getParentVersion()), relativePath, visited, depth + 1);
    }

    private String resolveRevision(UUID siteId, SiteThemeRevisionEntity revision, String relativePath, Set<UUID> visited, int depth) {
        checkTraversal(revision.getThemeId(), visited, depth); SiteThemeEntity theme = requireTheme(siteId, revision.getThemeId());
        String path = "themes/" + theme.getName() + "/" + relativePath;
        Optional<SiteThemeRevisionFileEntity> own = revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(revision.getId()).stream().filter(f -> path.equals(f.getPath())).findFirst();
        if (own.isPresent()) return new String(own.get().getContent(), StandardCharsets.UTF_8);
        if (revision.getParentThemeId() == null || revision.getParentThemeVersion() == null) return null;
        requireTheme(siteId, revision.getParentThemeId());
        return resolveRevision(siteId, requireRevision(revision.getParentThemeId(), revision.getParentThemeVersion()), relativePath, visited, depth + 1);
    }

    private String resolveReleaseRevision(SiteThemeRevisionEntity revision, String relativePath,
                                          com.powercms.cms.publish.ReleaseThemeContext context,
                                          Set<UUID> visited, int depth) {
        checkTraversal(revision.getId(), visited, depth);
        SiteThemeEntity theme = themeRepo.findById(revision.getThemeId()).orElseThrow(() -> invalid("\u4e3b\u9898\u4e0d\u5b58\u5728"));
        String path = "themes/" + theme.getName() + "/" + relativePath;
        Optional<SiteThemeRevisionFileEntity> own = revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(revision.getId()).stream()
            .filter(f -> path.equals(f.getPath())).findFirst();
        if (own.isPresent()) return new String(own.get().getContent(), StandardCharsets.UTF_8);
        if (revision.getParentThemeId() == null && revision.getParentThemeVersion() == null) return null;
        if (revision.getParentThemeId() == null || revision.getParentThemeVersion() == null)
            throw invalid("\u4e3b\u9898 revision \u7684\u7236\u4e3b\u9898\u5f15\u7528\u4e0d\u5b8c\u6574: " + revision.getId());
        var parentPin = context.require(revision.getParentThemeId());
        if (parentPin.themeVersion() != revision.getParentThemeVersion())
            throw invalid("Release \u7236\u4e3b\u9898 revision \u4e0e\u56fa\u5b9a\u7ee7\u627f\u7248\u672c\u4e0d\u4e00\u81f4");
        SiteThemeRevisionEntity parent = revisionRepo.findById(parentPin.revisionId())
            .filter(r -> r.getThemeId().equals(parentPin.themeId()) && r.getVersion() == parentPin.themeVersion())
            .orElseThrow(() -> invalid("Release \u56fa\u5b9a\u7684\u7236\u4e3b\u9898 revision \u4e0d\u5b58\u5728"));
        return resolveReleaseRevision(parent, relativePath, context, visited, depth + 1);
    }

    private void ensureNoCycle(UUID childId, SiteThemeEntity parent) {
        Set<UUID> seen = new HashSet<>(); seen.add(childId); SiteThemeEntity cursor = parent;
        for (int depth = 0; depth <= MAX_INHERITANCE_DEPTH; depth++) {
            if (!seen.add(cursor.getId())) throw invalid("\u4e3b\u9898\u7ee7\u627f\u5b58\u5728\u5faa\u73af");
            if (cursor.getParentThemeId() == null) return;
            cursor = themeRepo.findById(cursor.getParentThemeId()).orElseThrow(() -> invalid("\u7236\u4e3b\u9898\u4e0d\u5b58\u5728"));
        }
        throw invalid("\u4e3b\u9898\u7ee7\u627f\u6df1\u5ea6\u8d85\u8fc7 " + MAX_INHERITANCE_DEPTH);
    }
    private void checkTraversal(UUID id, Set<UUID> visited, int depth) { if (depth > MAX_INHERITANCE_DEPTH) throw invalid("\u4e3b\u9898\u7ee7\u627f\u6df1\u5ea6\u8d85\u8fc7 " + MAX_INHERITANCE_DEPTH); if (!visited.add(id)) throw invalid("\u4e3b\u9898\u7ee7\u627f\u5b58\u5728\u5faa\u73af"); }
    private String inheritanceCacheKey(SiteThemeEntity theme, Set<UUID> visited, int depth) { checkTraversal(theme.getId(), visited, depth); String key = theme.getId()+"@"+theme.getCurrentVersion(); if(theme.getParentThemeId()==null||theme.getParentVersion()==null)return key; SiteThemeEntity parent=themeRepo.findById(theme.getParentThemeId()).orElseThrow(()->invalid("\u7236\u4e3b\u9898\u4e0d\u5b58\u5728")); return key+">"+parent.getId()+"@"+theme.getParentVersion(); }


    private List<String> fragmentWarnings(Map<String, byte[]> files, Manifest manifest, boolean compatible) {
        String root = "themes/" + manifest.name() + "/";
        List<String> warnings = new ArrayList<>();
        for (String fragment : REQUIRED_FRAGMENTS) if (!files.containsKey(root + fragment)) warnings.add("缺少可选片段 " + fragment + "，渲染时将使用空内容");
        if (!compatible) warnings.add("主题要求 CMS " + manifest.cmsVersionRange() + "，当前版本为 " + CMS_VERSION + "；已恢复但不可绑定或激活");
        return warnings;
    }

    private void replaceLiveFiles(UUID themeId, Map<String, byte[]> data) {
        fileRepo.deleteByThemeId(themeId);
        fileRepo.flush();
        for (var e : data.entrySet()) {
            SiteThemeFileEntity f = new SiteThemeFileEntity();
            f.setThemeId(themeId);
            f.setPath(e.getKey());
            f.setMediaType(mediaType(e.getKey()));
            f.setSize(e.getValue().length);
            f.setSha256(sha256(e.getValue()));
            f.setContent(e.getValue());
            fileRepo.save(f);
        }
    }
    private void saveSnapshot(UUID revisionId, Map<String,byte[]> data) { for(var e:data.entrySet()){SiteThemeRevisionFileEntity f=new SiteThemeRevisionFileEntity();f.setThemeRevisionId(revisionId);f.setPath(e.getKey());f.setMediaType(mediaType(e.getKey()));f.setSize(e.getValue().length);f.setSha256(sha256(e.getValue()));f.setContent(Arrays.copyOf(e.getValue(),e.getValue().length));revisionFileRepo.save(f);} }
    private List<FileDigest> digestList(Map<String,byte[]> data){return data.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->new FileDigest(e.getKey(),mediaType(e.getKey()),e.getValue().length,sha256(e.getValue()))).toList();}
    private String snapshotDigest(Map<String,byte[]> data){StringBuilder b=new StringBuilder();digestList(data).forEach(f->b.append(f.path()).append('\0').append(f.sha256()).append('\n'));return sha256(b.toString().getBytes(StandardCharsets.UTF_8));}
    private SiteThemeEntity requireShareableOwnerTheme(UUID ownerSiteId, UUID themeId) {
        SiteThemeEntity theme = requireTheme(ownerSiteId, themeId);
        if (!"VALID".equals(theme.getValidity()) || theme.getCurrentVersion() <= 0) throw invalid("只有具有有效 revision 的 VALID 主题可共享");
        requireRevision(themeId, theme.getCurrentVersion()); return theme;
    }
    private SiteThemeShareEntity requireShare(UUID themeId, UUID consumerSiteId) { return shareRepo.findByThemeIdAndConsumerSiteId(themeId, consumerSiteId).orElseThrow(() -> invalid("主题共享记录不存在")); }
    private ShareInfo shareInfo(SiteThemeShareEntity s) { return new ShareInfo(s.getId(),s.getThemeId(),s.getConsumerSiteId(),s.getPinnedVersion(),s.getStatus(),s.getCreatedBy(),s.getCreatedAt(),s.getUpdatedAt()); }
    private SiteThemeRevisionEntity requireRevision(UUID themeId,int version){return revisionRepo.findByThemeIdAndVersion(themeId,version).orElseThrow(()->invalid("主题版本不存在: "+version));}
    private RevisionInfo revisionInfo(SiteThemeRevisionEntity r){return new RevisionInfo(r.getId(),r.getThemeId(),r.getVersion(),r.getParentVersion(),r.getParentThemeId(),r.getParentThemeVersion(),r.getAuthor(),r.getMessage(),r.getManifestSnapshot(),r.getFileDigest(),r.getCreatedAt());}
    private Map<String,String> snapshotHashes(SiteThemeRevisionEntity r){LinkedHashMap<String,String> m=new LinkedHashMap<>();revisionFileRepo.findByThemeRevisionIdOrderByPathAsc(r.getId()).forEach(f->m.put(f.getPath(),f.getSha256()));return m;}
    private Manifest readManifest(String value){try{return json.readValue(value,Manifest.class);}catch(Exception e){throw invalid("主题版本清单无效");}}
    private List<String> readWarnings(String value){try{return json.readValue(value,json.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){return List.of();}}
    private static BusinessException conflict(){return new BusinessException(ErrorCodes.CONTENT_VERSION_CONFLICT,"主题已被其他请求修改，请刷新后重试");}

    private LinkedHashMap<String, byte[]> readZip(byte[] archive) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>(); Set<String> folded = new HashSet<>(); long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = validatePath(entry.getName()); if (entry.isDirectory()) continue;
                if (total < 0) throw invalid("ZIP 解压大小溢出");
                if (files.size() >= MAX_FILES) throw invalid("ZIP 文件数超过 " + MAX_FILES + " 限制");
                String lower = path.toLowerCase(Locale.ROOT);
                if (files.containsKey(path)) throw invalid("ZIP 包含重复条目: " + path);
                if (!folded.add(lower)) throw invalid("ZIP 包含大小写冲突条目: " + path);
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; long size = 0;
                while ((n = zip.read(buf)) != -1) { size += n; total += n; if (size > MAX_FILE_BYTES) throw invalid("单文件解压超过 10 MiB: " + path); if (total > MAX_EXPANDED_BYTES) throw invalid("ZIP 累计解压超过 50 MiB"); out.write(buf, 0, n); }
                byte[] data = out.toByteArray(); scan(path, data); files.put(path, data);
            }
        } catch (BusinessException ex) { throw ex; } catch (IOException | IllegalArgumentException ex) { throw invalid("无法读取主题 ZIP: " + ex.getMessage()); }
        if (files.isEmpty()) throw invalid("主题 ZIP 不包含文件"); return files;
    }

    private String validatePath(String path) {
        if (path == null || path.isBlank() || CONTROL.matcher(path).find()) throw invalid("ZIP 条目路径包含控制字符或为空");
        if (path.indexOf('\\') >= 0) throw invalid("ZIP 条目路径不得使用反斜杠: " + path);
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) throw invalid("ZIP 条目不得使用绝对路径: " + path);
        String[] parts = path.split("/", -1); for (String p : parts) if (p.equals("..") || p.equals(".")) throw invalid("ZIP 条目存在路径穿越: " + path);
        return path;
    }
    private void scan(String path, byte[] data) {
        String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (file.equals(".env") || file.startsWith(".env.") || file.contains("credentials") || file.endsWith(".pem") || file.endsWith(".key")) throw invalid("主题包包含禁止的凭据/私钥文件: " + path);
        if (data.length <= 2 * 1024 * 1024) { String text = new String(data, StandardCharsets.UTF_8); if (PRIVATE_KEY.matcher(text).find()) throw invalid("主题包包含私钥内容: " + path); if (DANGEROUS.matcher(text).find()) throw invalid("主题脚本包含明显危险命令模式: " + path); }
    }
    private Manifest parseManifest(byte[] bytes) {
        try { Map<?,?> m = yaml.readValue(bytes, Map.class); return new Manifest(required(m,"name"), required(m,"version"), required(m,"vendor"), required(m,"cmsVersionRange")); }
        catch (BusinessException ex) { throw ex; } catch (Exception ex) { throw invalid("theme.yaml 不是合法 YAML: " + ex.getMessage()); }
    }
    private String required(Map<?,?> m, String key) { Object v=m.get(key); if(v==null||String.valueOf(v).isBlank()) throw invalid("theme.yaml 缺少必填字段 " + key); return String.valueOf(v).trim(); }
    private void validateThemeName(String name) { if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) throw invalid("theme.yaml name 仅允许字母、数字、点、下划线和连字符"); }
    private boolean compatible(String range) { if (range.equals("*") || range.equals("12.x") || range.equals("12.*")) return true; if (range.startsWith(">=")) return compareVersion(CMS_VERSION, range.substring(2).trim()) >= 0; return CMS_VERSION.equals(range); }
    private int compareVersion(String a,String b){int[] x=parts(a),y=parts(b);for(int i=0;i<3;i++){int c=Integer.compare(x[i],y[i]);if(c!=0)return c;}return 0;} private int[] parts(String v){String[] p=v.split("\\.");int[] n=new int[3];try{for(int i=0;i<Math.min(3,p.length);i++)n[i]=Integer.parseInt(p[i]);}catch(Exception e){throw invalid("不支持的 cmsVersionRange 版本: "+v);}return n;}
    private SiteThemeEntity requireTheme(UUID siteId, UUID id) { return themeRepo.findById(id).filter(t -> siteId.equals(t.getSiteId())).orElseThrow(() -> invalid("主题不存在或不属于当前站点")); }
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw invalid("主题元数据序列化失败");}}
    private static String mediaType(String p){String x=p.toLowerCase(Locale.ROOT);if(x.endsWith(".ftl"))return "text/x-freemarker";if(x.endsWith(".yaml")||x.endsWith(".yml"))return "application/yaml";if(x.endsWith(".css"))return "text/css";if(x.endsWith(".js"))return "text/javascript";if(x.endsWith(".png"))return "image/png";if(x.endsWith(".jpg")||x.endsWith(".jpeg"))return "image/jpeg";if(x.endsWith(".svg"))return "image/svg+xml";return MediaType.APPLICATION_OCTET_STREAM_VALUE;}
    private static String sha256(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException invalid(String m){return new BusinessException(ErrorCodes.VALIDATION_FAILED,m);}
}

