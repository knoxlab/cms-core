package com.powercms.cms.sitepackage;

import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.sitepackage.entity.TemplateFileEntity;
import com.powercms.cms.sitepackage.entity.TemplateProjectEntity;
import com.powercms.cms.sitepackage.entity.TemplateRevisionEntity;
import com.powercms.cms.sitepackage.repo.TemplateFileRepository;
import com.powercms.cms.sitepackage.repo.TemplateProjectRepository;
import com.powercms.cms.sitepackage.repo.TemplateRevisionRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class TemplateService {

    private final TemplateProjectRepository projectRepo;
    private final TemplateFileRepository fileRepo;
    private final TemplateRevisionRepository revisionRepo;
    private final ObjectMapper mapper;
    private final RenderDatasetCache datasetCache;

    TemplateService(TemplateProjectRepository projectRepo, TemplateFileRepository fileRepo,
                    TemplateRevisionRepository revisionRepo, ObjectMapper mapper,
                    RenderDatasetCache datasetCache) {
        this.projectRepo = projectRepo;
        this.fileRepo = fileRepo;
        this.revisionRepo = revisionRepo;
        this.mapper = mapper;
        this.datasetCache = datasetCache;
    }

    // ---------- Project ----------

    public record ProjInfo(UUID id, UUID siteId, String name, String projectType,
                           String runtimeImage, int currentRevision, Instant createdAt) {
        public static ProjInfo from(TemplateProjectEntity p) {
            return new ProjInfo(p.getId(), p.getSiteId(), p.getName(), p.getProjectType(),
                p.getRuntimeImage(), p.getCurrentRevision(), p.getCreatedAt());
        }
    }

    public ProjInfo createProject(UUID siteId, String name, String projectType) {
        // 矩阵 C3：社区版单站点 1 个模板工程。4 个内部调用方（包导入/MCP/预置种子）
        // 全部经由本方法，API 直调无法绕过。
        if (projectRepo.countBySiteId(siteId) >= 1) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "社区版单站点只支持 1 个模板工程");
        }
        var p = new TemplateProjectEntity();
        p.setSiteId(siteId);
        p.setName(name);
        p.setProjectType(projectType != null ? projectType : TemplateProjectEntity.TYPE_REACT);
        p.setRuntimeImage("node:20-alpine");
        p = projectRepo.save(p);
        return ProjInfo.from(p);
    }

    public List<ProjInfo> listProjects(UUID siteId) {
        return projectRepo.findBySiteId(siteId).stream().map(ProjInfo::from).toList();
    }

    public ProjInfo getProject(UUID id) { return ProjInfo.from(requireProject(id)); }

    // ---------- Files ----------

    public record FileInfo(String path, String content, String hash, Instant updatedAt) {
        public static FileInfo from(TemplateFileEntity f) {
            return new FileInfo(f.getPath(), f.getContent(), f.getFileHash(), f.getUpdatedAt());
        }
    }

    public record FileTree(List<FileInfo> files) {}

    public List<FileInfo> listFiles(UUID projectId) {
        return fileRepo.findByTemplateProjectIdOrderByPath(projectId).stream()
            .map(FileInfo::from).toList();
    }

    public String readFileContent(UUID projectId, String path) {
        return getFile(projectId, path).content();
    }
    public FileInfo getFile(UUID projectId, String path) {
        var f = fileRepo.findByTemplateProjectIdAndPath(projectId, path)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "文件不存在"));
        return FileInfo.from(f);
    }

    public FileInfo saveFile(UUID projectId, String path, String content) {
        sanitizePath(path);
        TemplateProjectEntity project = requireProject(projectId);
        enforceFtlViewPath(project.getProjectType(), path);
        var existing = fileRepo.findByTemplateProjectIdAndPath(projectId, path);
        var f = existing.orElseGet(() -> { var n = new TemplateFileEntity(); n.setTemplateProjectId(projectId); n.setPath(path); return n; });
        f.setContent(content);
        f.setFileHash(sha256(content));
        f.setUpdatedAt(Instant.now());
        f = fileRepo.save(f);
        datasetCache.invalidate(RenderDatasetCache.Region.TEMPLATE, projectId + ":" + path);

        updatePackageJsonIfNeeded(f);
        return FileInfo.from(f);
    }

    public void deleteFile(UUID projectId, String path) {
        sanitizePath(path);
        var f = fileRepo.findByTemplateProjectIdAndPath(projectId, path)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "文件不存在"));
        fileRepo.delete(f);
        datasetCache.invalidate(RenderDatasetCache.Region.TEMPLATE, projectId + ":" + path);
    }

    // ---------- Revision ----------

    public record RevisionInfo(UUID id, int version, Integer parentVersion,
                               String author, String message, String fileSnapshot, Instant createdAt) {
        public static RevisionInfo from(TemplateRevisionEntity r) {
            return new RevisionInfo(r.getId(), r.getVersion(), r.getParentVersion(),
                r.getAuthor(), r.getMessage(), r.getFileSnapshot(), r.getCreatedAt());
        }
    }

    public record DiffEntry(String path, String action) {} // action: ADDED / MODIFIED / DELETED

    /** 保存版本快照（FR-IDE-003）：将所有文件序列化为 JSON 快照 */
    @SuppressWarnings("unchecked")
    public RevisionInfo commit(UUID projectId, String author, String message) {
        var project = requireProject(projectId);
        int nextVer = project.getCurrentRevision() + 1;
        List<FileInfo> files = listFiles(projectId);

        String snapshot;
        try {
            snapshot = mapper.writeValueAsString(files);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "版本快照序列化失败");
        }

        var rev = TemplateRevisionEntity.create(project.getId(), nextVer,
            project.getCurrentRevision() > 0 ? project.getCurrentRevision() : null,
            author, message, snapshot);
        revisionRepo.save(rev);

        project.setCurrentRevision(nextVer);
        projectRepo.save(project);
        return RevisionInfo.from(rev);
    }

    public List<RevisionInfo> revisions(UUID projectId) {
        return revisionRepo.findByTemplateProjectIdOrderByVersionDesc(projectId).stream()
            .map(RevisionInfo::from).toList();
    }

    public RevisionInfo getRevision(UUID projectId, int version) {
        return revisionRepo.findByTemplateProjectIdAndVersion(projectId, version)
            .map(RevisionInfo::from)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "版本不存在"));
    }

    /** 恢复到指定版本（FR-IDE-003）：重建文件树 */
    @SuppressWarnings("unchecked")
    public void restore(UUID projectId, int version) {
        var rev = revisionRepo.findByTemplateProjectIdAndVersion(projectId, version)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "版本不存在"));
        try {
            List<Map<String, Object>> files = mapper.readValue(rev.getFileSnapshot(), List.class);
            for (var m : files) {
                String path = (String) m.get("path");
                String content = (String) m.get("content");
                saveFile(projectId, path, content);
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "版本快照解析失败");
        }
    }

    /** 计算两个版本之间的文件差异 */
    @SuppressWarnings("unchecked")
    public List<DiffEntry> diff(UUID projectId, int v1, int v2) {
        var r1 = requireRevision(projectId, v1);
        var r2 = requireRevision(projectId, v2);
        try {
            var set1 = parsePaths(r1.getFileSnapshot());
            var set2 = parsePaths(r2.getFileSnapshot());
            List<DiffEntry> diffs = new ArrayList<>();
            for (var p : set2) { if (!set1.contains(p)) diffs.add(new DiffEntry(p, "ADDED")); }
            for (var p : set1) { if (!set2.contains(p)) diffs.add(new DiffEntry(p, "DELETED")); }
            return diffs;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "差异计算失败");
        }
    }

    private Set<String> parsePaths(String snapshot) throws JsonProcessingException {
        List<Map<String, Object>> list = mapper.readValue(snapshot, List.class);
        return list.stream().map(m -> (String) m.get("path")).collect(Collectors.toSet());
    }

    // ========== FR-IDE-004: lockfile validation ==========

    public record LockfileStatus(boolean hasLockfile, String lockfileHash,
                                  boolean hasPackageJson, boolean matchValid) {
        public boolean isReleaseReady() { return hasLockfile && hasPackageJson && matchValid; }
    }

    /** 检查 lockfile 是否存在及与 package.json 的一致性 */
    public LockfileStatus validateLockfile(UUID projectId) {
        var proj = requireProject(projectId);
        if (proj.getLockfileHash() == null) {
            return new LockfileStatus(false, null,
                proj.getPackageJson() != null, false);
        }
        // re-derive current lockfile hash from stored files
        var lockOpt = fileRepo.findByTemplateProjectIdAndPath(projectId, "package-lock.json")
            .or(() -> fileRepo.findByTemplateProjectIdAndPath(projectId, "yarn.lock"));
        var pkgOpt = fileRepo.findByTemplateProjectIdAndPath(projectId, "package.json");
        if (lockOpt.isEmpty()) {
            // lockfile was deleted from fs
            proj.setLockfileHash(null);
            projectRepo.save(proj);
            return new LockfileStatus(false, null, pkgOpt.isPresent(), false);
        }
        String currentLockHash = sha256(lockOpt.get().getContent());
        boolean match = currentLockHash.equals(proj.getLockfileHash());
        return new LockfileStatus(true, currentLockHash,
            proj.getPackageJson() != null, match);
    }

    public record PreflightResult(boolean pass, List<String> items) {}

    /** 发布前门禁检查（FR-IDE-004）：lockfile 缺失/不一致时阻断发布构建 */
    public PreflightResult preflightRelease(UUID projectId) {
        var items = new ArrayList<String>();
        boolean pass = true;

        var ls = validateLockfile(projectId);
        if (!ls.hasLockfile()) {
            items.add("BLOCKED: 工程缺少 lockfile，发布构建禁止浮动依赖");
            pass = false;
        } else if (!ls.matchValid()) {
            items.add("BLOCKED: lockfile 与 package manifest 不一致，构建失败");
            pass = false;
        } else {
            items.add("PASS: lockfile 校验通过");
        }
        if (!ls.hasPackageJson()) {
            items.add("WARN: 工程无 package.json");
        }
        return new PreflightResult(pass, items);
    }

    // ========== FR-IDE-008: dependency audit ==========

    @SuppressWarnings("unchecked")
    public record DependencyReport(String runtimeImage, String projectType, List<DepInfo> dependencies) {
        public record DepInfo(String name, String version, String license) {}
    }

    /** 从 package.json 解析依赖清单、许可证和运行时镜像摘要（FR-IDE-008） */
    @SuppressWarnings("unchecked")
    public DependencyReport auditDependencies(UUID projectId) {
        var proj = requireProject(projectId);
        List<DependencyReport.DepInfo> deps = new ArrayList<>();

        var pkgOpt = fileRepo.findByTemplateProjectIdAndPath(projectId, "package.json");
        if (pkgOpt.isPresent()) {
            try {
                Map<String, Object> pkg = mapper.readValue(pkgOpt.get().getContent(), Map.class);
                addDeps(deps, pkg, "dependencies");
                addDeps(deps, pkg, "devDependencies");
                addDeps(deps, pkg, "peerDependencies");
            } catch (Exception ignored) {
                // unparseable package.json
            }
        }
        return new DependencyReport(proj.getRuntimeImage(), proj.getProjectType(), deps);
    }

    @SuppressWarnings("unchecked")
    private void addDeps(List<DependencyReport.DepInfo> deps, Map<String, Object> pkg, String section) {
        Object raw = pkg.get(section);
        if (raw instanceof Map) {
            ((Map<String, Object>) raw).forEach((k, v) ->
                deps.add(new DependencyReport.DepInfo(k, v.toString(), "UNKNOWN")));
        }
    }

    // ---------- helpers ----------

    /** FR-LANG-001：视图模板扩展名必须为 .ftl。 */
    public static void requireFtlViewPath(String path) {
        if (path == null || path.isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "视图模板路径不能为空");
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path.trim();
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".ftl")) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST,
                "视图模板必须使用 .ftl 扩展名: " + path);
        }
    }

    private void enforceFtlViewPath(String projectType, String path) {
        if (!"FTL".equalsIgnoreCase(projectType)) return;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vtl") || lower.endsWith(".vm") || lower.endsWith(".btl")) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST,
                "FTL 工程禁止保存 Velocity/Beetl 视图，请使用 .ftl: " + path);
        }
        if (isViewTemplateCandidate(lower)) requireFtlViewPath(path);
    }

    private static boolean isViewTemplateCandidate(String lowerPath) {
        return lowerPath.endsWith(".ftl")
            || lowerPath.endsWith(".html")
            || lowerPath.endsWith(".htm")
            || lowerPath.contains("templates/")
            || lowerPath.startsWith("pages/")
            || lowerPath.startsWith("containers/")
            || lowerPath.startsWith("components/");
    }

    private void sanitizePath(String path) {
        if (path.contains("..") || path.startsWith("/") || path.contains("\\")) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "非法路径：" + path);
        }
    }

    private void updatePackageJsonIfNeeded(TemplateFileEntity f) {
        if ("package.json".equals(f.getPath())) {
            projectRepo.findById(f.getTemplateProjectId()).ifPresent(p -> {
                p.setPackageJson(f.getContent());
                p.setLockfileHash(null); // lockfile changed signal
                projectRepo.save(p);
            });
        }
        if (f.getPath().endsWith("package-lock.json") || f.getPath().endsWith("yarn.lock")) {
            projectRepo.findById(f.getTemplateProjectId()).ifPresent(p -> {
                p.setLockfileHash(f.getFileHash());
                projectRepo.save(p);
            });
        }
    }

    TemplateProjectEntity requireProject(UUID id) {
        return projectRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "工程不存在"));
    }

    TemplateRevisionEntity requireRevision(UUID projectId, int version) {
        return revisionRepo.findByTemplateProjectIdAndVersion(projectId, version)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "版本不存在"));
    }

    static String sha256(String content) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
