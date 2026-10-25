package com.powercms.cms.media;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.media.entity.MediaFolderEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaFolderRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 站点媒体目录（FR-AGT-MEDIA-005）：目录树的一等公民读写。
 *
 * <p>路径口径与 {@code media_asset.folder_path} 完全一致（{@code /a/b}，根为空/根标记 {@code /}），
 * 因此目录搬移只做前缀重写即可让资产与目录树保持一致，无需迁移数据。
 *
 * <p>安全边界：站点域内定位（跨站点一律 NOT_FOUND）、路径规范化拒绝 {@code ..} 与非法字符、
 * 父目录存在性校验、环（把目录搬到自身/后代）拒绝。目录级 ACL 不在本批次范围，门禁由站点能力承担。
 */
@Service
@Transactional
public class MediaFolderService {

    /** 根目录标记：不落库，作为「无目录」的显式入参值。 */
    public static final String ROOT_PATH = "/";

    private final MediaFolderRepository folders;
    private final MediaAssetRepository assets;
    private final AuditService audit;

    public MediaFolderService(MediaFolderRepository folders, MediaAssetRepository assets, AuditService audit) {
        this.folders = folders;
        this.assets = assets;
        this.audit = audit;
    }

    /** 目录视图：depth 由物化路径段数推导，资产数含子目录汇总（供搬移/删除影响说明）。 */
    public record FolderView(UUID id, UUID siteId, UUID parentId, String name, String path, int sortOrder,
                             int depth, long directAssetCount, long totalAssetCount,
                             Instant createdAt, Instant updatedAt) {}

    /** 目录树（按物化路径字典序，天然父先于子）。 */
    public List<FolderView> tree(UUID siteId) {
        List<MediaFolderEntity> all = folders.findBySiteIdOrderByPathAsc(siteId);
        Map<String, long[]> counts = folderAssetCounts(siteId);
        List<FolderView> views = new ArrayList<>(all.size());
        for (MediaFolderEntity f : all) {
            long[] c = counts.getOrDefault(f.getPath(), new long[]{0L, 0L});
            views.add(toView(f, c[0], c[1]));
        }
        return views;
    }

    public FolderView get(UUID siteId, UUID folderId) {
        MediaFolderEntity folder = require(siteId, folderId);
        Map<String, long[]> counts = folderAssetCounts(siteId);
        long[] c = counts.getOrDefault(folder.getPath(), new long[]{0L, 0L});
        return toView(folder, c[0], c[1]);
    }

    public FolderView findByPath(UUID siteId, String rawPath) {
        String path = normalizePath(rawPath);
        if (ROOT_PATH.equals(path)) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "根目录不是目录实体");
        }
        MediaFolderEntity folder = folders.findBySiteIdAndPath(siteId, path)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体目录不存在：" + path));
        Map<String, long[]> counts = folderAssetCounts(siteId);
        long[] c = counts.getOrDefault(folder.getPath(), new long[]{0L, 0L});
        return toView(folder, c[0], c[1]);
    }

    /** 幂等创建（含父目录）：上传透传 folder_path 时使用，保证目录树与资产挂载点不脱节。 */
    public FolderView ensure(UUID siteId, String rawPath, String actor) {
        String path = normalizePath(rawPath);
        if (ROOT_PATH.equals(path)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "根目录无需创建");
        }
        String[] segments = segments(path);
        UUID parentId = null;
        StringBuilder current = new StringBuilder();
        MediaFolderEntity last = null;
        for (String segment : segments) {
            current.append('/').append(segment);
            String currentPath = current.toString();
            MediaFolderEntity existing = folders.findBySiteIdAndPath(siteId, currentPath).orElse(null);
            if (existing == null) {
                MediaFolderEntity created = new MediaFolderEntity();
                created.setSiteId(siteId);
                created.setParentId(parentId);
                created.setName(segment);
                created.setPath(currentPath);
                created.setSortOrder((int) nextSortOrder(siteId, parentId));
                last = folders.save(created);
                audit.write(actor, "media.folder.create", "MediaFolder", last.getId().toString(), siteId,
                    "success", "媒体目录创建 path=" + currentPath);
            } else {
                last = existing;
            }
            parentId = last.getId();
        }
        return get(siteId, Objects.requireNonNull(last).getId());
    }

    /** 显式创建子目录（管理台/Agent 走这里；上传透传走 {@link #ensure}）。 */
    public FolderView create(UUID siteId, UUID parentId, String rawName, Integer sortOrder, String actor) {
        String name = validateName(rawName);
        MediaFolderEntity parent = null;
        if (parentId != null) {
            parent = require(siteId, parentId);
        }
        String path = parent == null ? "/" + name : parent.getPath() + "/" + name;
        if (folders.existsBySiteIdAndPath(siteId, path)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "媒体目录已存在：" + path);
        }
        MediaFolderEntity folder = new MediaFolderEntity();
        folder.setSiteId(siteId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setPath(path);
        folder.setSortOrder(sortOrder == null ? (int) nextSortOrder(siteId, parentId) : sortOrder);
        folder = folders.save(folder);
        audit.write(actor, "media.folder.create", "MediaFolder", folder.getId().toString(), siteId,
            "success", "媒体目录创建 path=" + path + " parent=" + (parentId == null ? "root" : parentId));
        return get(siteId, folder.getId());
    }

    /** 目录搬移：拒绝成环，重写后代目录与挂载资产的前缀，保持树与资产一致。 */
    public FolderView move(UUID siteId, UUID folderId, UUID newParentId, String actor) {
        MediaFolderEntity folder = require(siteId, folderId);
        MediaFolderEntity target = null;
        if (newParentId != null) {
            target = require(siteId, newParentId);
            if (target.getId().equals(folder.getId())) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不能把目录移动到自身");
            }
            if (target.getPath().equals(folder.getPath())
                || target.getPath().startsWith(folder.getPath() + "/")) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不能把目录移动到其子目录下");
            }
        }
        String oldPath = folder.getPath();
        String newPath = target == null ? "/" + folder.getName() : target.getPath() + "/" + folder.getName();
        if (oldPath.equals(newPath)) {
            return get(siteId, folder.getId());
        }
        if (folders.existsBySiteIdAndPath(siteId, newPath)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "目标位置已存在同名目录：" + newPath);
        }
        int rewrittenAssets = assets.rewriteFolderPathPrefix(siteId, oldPath, newPath, oldPath.length());
        int rewrittenFolders = folders.rewriteDescendantPaths(siteId, oldPath, newPath, oldPath.length());
        folder = require(siteId, folderId);
        folder.setParentId(newParentId);
        folder.setPath(newPath);
        folders.save(folder);
        audit.write(actor, "media.folder.move", "MediaFolder", folderId.toString(), siteId, "success",
            "媒体目录搬移 " + oldPath + " -> " + newPath + " folders=" + (rewrittenFolders + 1)
                + " assets=" + rewrittenAssets);
        return get(siteId, folder.getId());
    }

    /** 目录删除未开放（避免资产悬挂）：仅支持搬移；如需删除须先清空挂载资产。 */

    /** 物化路径是否已存在（工具 dry-run 用；根目录恒存在）。 */
    public boolean existsByPath(UUID siteId, String rawPath) {
        String path = normalizePath(rawPath);
        if (ROOT_PATH.equals(path)) {
            return true;
        }
        return folders.existsBySiteIdAndPath(siteId, path);
    }

    /** 父路径推导：{@code /a/b} → {@code /a}；顶层目录返回 null（父为根）。 */
    public static String parentPathOf(String path) {
        if (path == null || path.isBlank() || ROOT_PATH.equals(path)) {
            return null;
        }
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? null : path.substring(0, idx);
    }

    // ── 路径与校验 ────────────────────────────────────────────────────────

    /** 物化路径规范化：统一分隔符、去重斜杠、去尾斜杠；拒绝 {@code ..} 与控制字符。 */
    public static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return ROOT_PATH;
        }
        String path = raw.trim().replace('\\', '/');
        if (path.contains("..")) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "媒体目录路径不允许包含 ..");
        }
        path = path.replaceAll("/{2,}", "/");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() > 500) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "媒体目录路径超长（上限 500）");
        }
        for (String segment : segments(path)) {
            validateName(segment);
        }
        return path;
    }

    private static String[] segments(String path) {
        if (ROOT_PATH.equals(path)) {
            return new String[0];
        }
        return path.substring(1).split("/");
    }

    private static String validateName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "目录名不能为空");
        }
        String name = raw.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "目录名不允许包含路径分隔符或 ..：" + name);
        }
        if (name.length() > 150) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "目录名超长（上限 150）：" + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "目录名不允许包含控制字符");
            }
        }
        return name;
    }

    private MediaFolderEntity require(UUID siteId, UUID folderId) {
        return folders.findByIdAndSiteId(folderId, siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "媒体目录不存在"));
    }

    private long nextSortOrder(UUID siteId, UUID parentId) {
        return parentId == null
            ? folders.countBySiteIdAndParentIdIsNull(siteId)
            : folders.countBySiteIdAndParentId(siteId, parentId);
    }

    /** 一次读取站点全部资产挂载点，内存汇总为「直接挂载数 + 含子目录汇总数」。 */
    private Map<String, long[]> folderAssetCounts(UUID siteId) {
        Map<String, long[]> direct = new LinkedHashMap<>();
        for (String folderPath : assets.findFolderPathsBySite(siteId)) {
            if (folderPath == null || folderPath.isBlank()) {
                continue;
            }
            String path = normalizePath(folderPath);
            long[] c = direct.computeIfAbsent(path, k -> new long[]{0L, 0L});
            c[0]++;
            c[1]++;
        }
        Map<String, long[]> result = new LinkedHashMap<>(direct);
        for (Map.Entry<String, long[]> e : direct.entrySet()) {
            String path = e.getKey();
            int idx = path.lastIndexOf('/');
            while (idx > 0) {
                String ancestor = path.substring(0, idx);
                long[] c = result.computeIfAbsent(ancestor, k -> new long[]{0L, 0L});
                c[1] += e.getValue()[0];
                idx = ancestor.lastIndexOf('/');
            }
        }
        return result;
    }

    private FolderView toView(MediaFolderEntity f, long direct, long total) {
        return new FolderView(f.getId(), f.getSiteId(), f.getParentId(), f.getName(), f.getPath(),
            f.getSortOrder(), depthOf(f.getPath()), direct, total, f.getCreatedAt(), f.getUpdatedAt());
    }

    private static int depthOf(String path) {
        if (ROOT_PATH.equals(path) || path.isBlank()) {
            return 0;
        }
        return path.substring(1).split("/").length;
    }

    /** 目录清单排序（供控制器/工具稳定输出）。 */
    public static Comparator<FolderView> byPath() {
        return Comparator.comparing(FolderView::path);
    }
}
