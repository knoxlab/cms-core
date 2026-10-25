package com.powercms.cms.content;

import com.powercms.cms.content.entity.CategoryEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class CategoryService {

    private final CategoryRepository catRepo;
    private final ContentItemRepository itemRepo;

    CategoryService(CategoryRepository catRepo, ContentItemRepository itemRepo) {
        this.catRepo = catRepo;
        this.itemRepo = itemRepo;
    }

    public record CategoryInfo(UUID id, UUID parentId, String name, String pathAlias, int sortOrder,
                               int depth, List<CategoryInfo> children) {}

    public CategoryInfo create(UUID siteId, UUID parentId, String name, String pathAlias) {
        return create(siteId, parentId, name, pathAlias, null);
    }

    public CategoryInfo create(UUID siteId, UUID parentId, String name, String pathAlias, Integer sortOrder) {
        int depth = 0;
        if (parentId != null) {
            CategoryEntity parent = require(parentId);
            depth = parent.getDepth() + 1;
        }
        CategoryEntity cat = new CategoryEntity();
        cat.setSiteId(siteId);
        cat.setParentId(parentId);
        cat.setName(name);
        cat.setPathAlias(pathAlias);
        cat.setSortOrder(sortOrder != null ? sortOrder : 0);
        cat.setDepth(depth);
        cat = catRepo.save(cat);
        return toInfo(cat);
    }

    public List<CategoryInfo> tree(UUID siteId) {
        List<CategoryEntity> all = catRepo.findBySiteId(siteId);
        return buildTree(all, null);
    }

    public CategoryInfo update(UUID id, String name, String pathAlias, Integer sortOrder) {
        CategoryEntity cat = require(id);
        if (name != null && !name.isBlank()) {
            cat.setName(name.trim());
        }
        if (pathAlias != null) {
            cat.setPathAlias(pathAlias.isBlank() ? null : pathAlias.trim());
        }
        if (sortOrder != null) {
            cat.setSortOrder(sortOrder);
        }
        return toInfo(catRepo.save(cat));
    }

    public void move(UUID id, UUID newParentId) {
        move(id, newParentId, null);
    }

    public CategoryInfo move(UUID id, UUID newParentId, Integer sortOrder) {
        CategoryEntity cat = require(id);
        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new BusinessException(ErrorCodes.CONFLICT, "不能移动到自身");
            }
            if (isDescendant(id, newParentId)) {
                throw new BusinessException(ErrorCodes.CONFLICT, "不能移动到自身后代");
            }
        }
        int depth = newParentId == null ? 0 : require(newParentId).getDepth() + 1;
        cat.setParentId(newParentId);
        cat.setDepth(depth);
        if (sortOrder != null) {
            cat.setSortOrder(sortOrder);
        }
        return toInfo(catRepo.save(cat));
    }

    /** Reorder siblings under the same parent; orderedIds is the desired order. */
    public void reorder(UUID siteId, UUID parentId, List<UUID> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return;
        }
        List<CategoryEntity> siblings = catRepo.findBySiteId(siteId).stream()
            .filter(c -> parentId == null ? c.getParentId() == null : parentId.equals(c.getParentId()))
            .toList();
        var byId = siblings.stream().collect(Collectors.toMap(CategoryEntity::getId, c -> c));
        int i = 0;
        for (UUID id : orderedIds) {
            CategoryEntity cat = byId.get(id);
            if (cat == null) {
                throw new BusinessException(ErrorCodes.CONFLICT, "排序列表包含非同级分类");
            }
            cat.setSortOrder(i++);
            catRepo.save(cat);
        }
    }

    public void delete(UUID id) {
        CategoryEntity cat = require(id);
        List<CategoryEntity> children = catRepo.findBySiteIdAndParentIdOrderBySortOrder(cat.getSiteId(), id);
        for (CategoryEntity child : children) {
            delete(child.getId());
        }
        if (!itemRepo.findByCategoryId(id).isEmpty()) {
            throw new BusinessException(ErrorCodes.CONFLICT, "分类下有内容，无法删除");
        }
        catRepo.delete(cat);
    }

    // ==== Agent 站点写面（FR-AGT-SITE-001）：站点范围内栏目读写与破坏性影响评估 ====

    /** 站点范围内读取栏目；越站按不存在处理（不泄露其他站点对象）。 */
    public CategoryInfo getInSite(UUID siteId, UUID id) {
        return toInfo(requireInSite(siteId, id));
    }

    /** 站点范围内创建栏目；父栏目必须属于同一站点。 */
    public CategoryInfo createInSite(UUID siteId, UUID parentId, String name, String pathAlias, Integer sortOrder) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "栏目名称不能为空");
        }
        if (parentId != null) {
            requireInSite(siteId, parentId);
        }
        return create(siteId, parentId, name.trim(), normalizeAlias(pathAlias), sortOrder);
    }

    /**
     * 站点范围内更新栏目：可同时改名、改路径别名、改排序，或移动到新父栏目。
     * {@code parentProvided=true} 且 {@code newParentId=null} 表示移动到顶层；
     * 移动时校验目标父栏目同站且不是自身/自身后代。
     */
    public CategoryInfo updateInSite(UUID siteId, UUID id, boolean parentProvided, UUID newParentId, String name,
                                     String pathAlias, Integer sortOrder) {
        CategoryEntity cat = requireInSite(siteId, id);
        if (parentProvided && !Objects.equals(newParentId, cat.getParentId())) {
            if (newParentId != null && newParentId.equals(id)) {
                throw new BusinessException(ErrorCodes.CONFLICT, "不能移动到自身");
            }
            if (newParentId != null && isDescendant(id, newParentId)) {
                throw new BusinessException(ErrorCodes.CONFLICT, "不能移动到自身后代");
            }
            int depth = newParentId == null ? 0 : requireInSite(siteId, newParentId).getDepth() + 1;
            cat.setParentId(newParentId);
            cat.setDepth(depth);
        }
        if (name != null) {
            if (name.isBlank()) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "栏目名称不能为空");
            }
            cat.setName(name.trim());
        }
        if (pathAlias != null) {
            cat.setPathAlias(normalizeAlias(pathAlias));
        }
        if (sortOrder != null) {
            cat.setSortOrder(sortOrder);
        }
        return toInfo(catRepo.save(cat));
    }

    /**
     * 破坏性影响评估（FR-AGT-SITE-001，dry-run 用）：统计目标栏目子树规模与关联内容条数。
     * 移动父栏目或改路径别名视为破坏性变更（影响导航层级与路由前缀/外链），须在预览中明示。
     */
    public ColumnImpact assessImpact(UUID siteId, UUID id, boolean parentProvided, UUID newParentId, String newPathAlias) {
        CategoryEntity cat = requireInSite(siteId, id);
        Set<UUID> subtree = subtreeIds(catRepo.findBySiteId(siteId), id);
        long contents = itemRepo.countByCategoryIdIn(subtree);
        int descendants = subtree.size() - 1;
        boolean parentChanged = parentProvided && !Objects.equals(newParentId, cat.getParentId());
        boolean aliasChanged = newPathAlias != null && !newPathAlias.equals(cat.getPathAlias());
        boolean destructive = parentChanged || aliasChanged;
        StringBuilder summary = new StringBuilder();
        summary.append("栏目「").append(cat.getName()).append("」当前层级 ").append(cat.getDepth())
            .append("、路径别名 ").append(cat.getPathAlias() == null ? "(无)" : cat.getPathAlias())
            .append("；子栏目 ").append(descendants).append(" 个、关联内容 ").append(contents).append(" 条");
        if (parentChanged) {
            summary.append("；父栏目变更将改变导航层级与面包屑");
        }
        if (aliasChanged) {
            summary.append("；路径别名变更将改变该栏目下内容的路由前缀与既有外链");
        }
        return new ColumnImpact(cat.getId(), cat.getName(), cat.getPathAlias(), cat.getDepth(),
            descendants, contents, destructive, summary.toString());
    }

    /** 栏目变更影响视图（FR-AGT-SITE-001）。 */
    public record ColumnImpact(UUID columnId, String name, String pathAlias, int depth,
                               int descendantColumns, long contentsAffected,
                               boolean destructive, String summary) {}

    /** 站点范围内取栏目实体；越站按不存在处理。 */
    CategoryEntity requireInSite(UUID siteId, UUID id) {
        CategoryEntity cat = require(id);
        if (!cat.getSiteId().equals(siteId)) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "栏目不存在");
        }
        return cat;
    }

    private Set<UUID> subtreeIds(List<CategoryEntity> all, UUID root) {
        Set<UUID> ids = new LinkedHashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (!ids.add(current)) {
                continue;
            }
            for (CategoryEntity c : all) {
                if (current.equals(c.getParentId())) {
                    queue.add(c.getId());
                }
            }
        }
        return ids;
    }

    private static String normalizeAlias(String pathAlias) {
        return pathAlias == null || pathAlias.isBlank() ? null : pathAlias.trim();
    }

    CategoryEntity require(UUID id) {
        return catRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "分类不存在"));
    }

    private boolean isDescendant(UUID ancestorId, UUID descendantId) {
        CategoryEntity current = require(descendantId);
        while (current.getParentId() != null) {
            if (current.getParentId().equals(ancestorId)) return true;
            current = require(current.getParentId());
        }
        return false;
    }

    private List<CategoryInfo> buildTree(List<CategoryEntity> all, UUID parentId) {
        return all.stream().filter(c -> (parentId == null && c.getParentId() == null)
            || (parentId != null && parentId.equals(c.getParentId())))
            .sorted(Comparator.comparingInt(CategoryEntity::getSortOrder)
                .thenComparing(CategoryEntity::getName, Comparator.nullsLast(String::compareTo)))
            .map(c -> new CategoryInfo(c.getId(), c.getParentId(), c.getName(),
                c.getPathAlias(), c.getSortOrder(), c.getDepth(),
                buildTree(all, c.getId()))).toList();
    }

    private CategoryInfo toInfo(CategoryEntity c) {
        return new CategoryInfo(c.getId(), c.getParentId(), c.getName(),
            c.getPathAlias(), c.getSortOrder(), c.getDepth(), List.of());
    }
}
