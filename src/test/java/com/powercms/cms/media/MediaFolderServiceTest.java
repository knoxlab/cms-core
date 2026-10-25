package com.powercms.cms.media;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.media.entity.MediaFolderEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaFolderRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-AGT-MEDIA-005：站点媒体目录——
 * 路径规范化/穿越拒绝、同级重名冲突、成环拒绝、搬移前缀重写、目录资产数汇总（含子目录）、ensure 幂等。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaFolderServiceTest {

    @Mock MediaFolderRepository folders;
    @Mock MediaAssetRepository assets;
    @Mock AuditService audit;

    private MediaFolderService service;
    private final UUID siteId = UUID.randomUUID();
    private final Map<UUID, MediaFolderEntity> registry = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new MediaFolderService(folders, assets, audit);
        when(folders.save(any(MediaFolderEntity.class))).thenAnswer(inv -> {
            MediaFolderEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            registry.put(entity.getId(), entity);
            return entity;
        });
        when(folders.findByIdAndSiteId(any(), eq(siteId)))
            .thenAnswer(inv -> Optional.ofNullable(registry.get(inv.getArgument(0))));
    }

    // ── 路径规范化 ────────────────────────────────────────────────────────

    @Test
    void normalizesPathsAndRejectsTraversal() {
        assertThat(MediaFolderService.normalizePath(null)).isEqualTo("/");
        assertThat(MediaFolderService.normalizePath("  ")).isEqualTo("/");
        assertThat(MediaFolderService.normalizePath("images")).isEqualTo("/images");
        assertThat(MediaFolderService.normalizePath("/images//2026/")).isEqualTo("/images/2026");
        assertThat(MediaFolderService.normalizePath("\\images\\2026")).isEqualTo("/images/2026");
        assertThatThrownBy(() -> MediaFolderService.normalizePath("/images/../etc"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("..")
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThatThrownBy(() -> MediaFolderService.normalizePath("/" + "a".repeat(200)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("超长");
    }

    @Test
    void derivesParentPath() {
        assertThat(MediaFolderService.parentPathOf("/images/2026/09")).isEqualTo("/images/2026");
        assertThat(MediaFolderService.parentPathOf("/images")).isNull();
        assertThat(MediaFolderService.parentPathOf("/")).isNull();
    }

    // ── 创建 ──────────────────────────────────────────────────────────────

    @Test
    void createRejectsDuplicatePath() {
        when(folders.existsBySiteIdAndPath(siteId, "/images")).thenReturn(true);

        assertThatThrownBy(() -> service.create(siteId, null, "images", null, "actor"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已存在")
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.CONFLICT);
        verify(folders, never()).save(any());
    }

    @Test
    void createRejectsBlankOrIllegalName() {
        assertThatThrownBy(() -> service.create(siteId, null, "  ", null, "actor"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.create(siteId, null, "a/b", null, "actor"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("分隔符");
    }

    @Test
    void createChildUnderParentMaterializesPathAndAudits() {
        MediaFolderEntity parent = folder("images", null, "/images", 1);
        registry.put(parent.getId(), parent);
        when(folders.existsBySiteIdAndPath(siteId, "/images/2026")).thenReturn(false);
        when(assets.findFolderPathsBySite(siteId)).thenReturn(List.of());

        MediaFolderService.FolderView view = service.create(siteId, parent.getId(), "2026", null, "actor");

        assertThat(view.path()).isEqualTo("/images/2026");
        assertThat(view.parentId()).isEqualTo(parent.getId());
        assertThat(view.depth()).isEqualTo(2);
        verify(audit).write(eq("actor"), eq("media.folder.create"), eq("MediaFolder"), anyString(),
            eq(siteId), eq("success"), anyString());
    }

    @Test
    void createRejectsParentFromAnotherSite() {
        UUID foreign = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(siteId, foreign, "child", null, "actor"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("媒体目录不存在");
    }

    // ── 搬移 ──────────────────────────────────────────────────────────────

    @Test
    void moveRejectsCycles() {
        MediaFolderEntity images = folder("images", null, "/images", 1);
        MediaFolderEntity year = folder("2026", images.getId(), "/images/2026", 2);
        registry.put(images.getId(), images);
        registry.put(year.getId(), year);

        assertThatThrownBy(() -> service.move(siteId, images.getId(), year.getId(), "actor"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("子目录");
        assertThatThrownBy(() -> service.move(siteId, images.getId(), images.getId(), "actor"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("自身");
    }

    @Test
    void moveRewritesDescendantAndAssetPrefixes() {
        MediaFolderEntity source = folder("images", null, "/images", 1);
        MediaFolderEntity target = folder("archive", null, "/archive", 1);
        registry.put(source.getId(), source);
        registry.put(target.getId(), target);
        when(folders.existsBySiteIdAndPath(siteId, "/archive/images")).thenReturn(false);
        when(assets.findFolderPathsBySite(siteId)).thenReturn(List.of());

        MediaFolderService.FolderView view = service.move(siteId, source.getId(), target.getId(), "actor");

        verify(assets).rewriteFolderPathPrefix(siteId, "/images", "/archive/images", "/images".length());
        verify(folders).rewriteDescendantPaths(siteId, "/images", "/archive/images", "/images".length());
        assertThat(view.path()).isEqualTo("/archive/images");
        assertThat(view.parentId()).isEqualTo(target.getId());
    }

    // ── 目录树与资产计数 ──────────────────────────────────────────────────

    @Test
    void treeAggregatesAssetCountsIncludingDescendants() {
        MediaFolderEntity images = folder("images", null, "/images", 1);
        MediaFolderEntity year = folder("2026", images.getId(), "/images/2026", 2);
        when(folders.findBySiteIdOrderByPathAsc(siteId)).thenReturn(List.of(images, year));
        when(assets.findFolderPathsBySite(siteId))
            .thenReturn(java.util.Arrays.asList("/images", "/images/2026", "/images/2026", null, "  "));

        List<MediaFolderService.FolderView> tree = service.tree(siteId);

        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).path()).isEqualTo("/images");
        assertThat(tree.get(0).directAssetCount()).isEqualTo(1);
        assertThat(tree.get(0).totalAssetCount()).isEqualTo(3);
        assertThat(tree.get(1).path()).isEqualTo("/images/2026");
        assertThat(tree.get(1).directAssetCount()).isEqualTo(2);
        assertThat(tree.get(1).totalAssetCount()).isEqualTo(2);
    }

    @Test
    void ensureIsIdempotentWhenAllSegmentsExist() {
        MediaFolderEntity images = folder("images", null, "/images", 1);
        registry.put(images.getId(), images);
        when(folders.findBySiteIdAndPath(siteId, "/images")).thenReturn(Optional.of(images));
        when(assets.findFolderPathsBySite(siteId)).thenReturn(List.of());

        MediaFolderService.FolderView view = service.ensure(siteId, "images", "actor");

        assertThat(view.id()).isEqualTo(images.getId());
        verify(folders, never()).save(any());
    }

    @Test
    void ensureCreatesMissingParents() {
        when(folders.findBySiteIdAndPath(siteId, "/images")).thenReturn(Optional.empty());
        when(folders.findBySiteIdAndPath(siteId, "/images/2026")).thenReturn(Optional.empty());
        when(folders.countBySiteIdAndParentIdIsNull(siteId)).thenReturn(0L);
        when(folders.countBySiteIdAndParentId(eq(siteId), any())).thenReturn(0L);
        when(assets.findFolderPathsBySite(siteId)).thenReturn(List.of());

        MediaFolderService.FolderView view = service.ensure(siteId, "images/2026", "actor");

        assertThat(view.path()).isEqualTo("/images/2026");
        verify(folders, times(2)).save(any(MediaFolderEntity.class));
    }

    @Test
    void rootPathExistsButHasNoEntity() {
        assertThat(service.existsByPath(siteId, "/")).isTrue();
        assertThatThrownBy(() -> service.findByPath(siteId, "/"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("根目录");
    }

    private static MediaFolderEntity folder(String name, UUID parentId, String path, int sortOrder) {
        MediaFolderEntity entity = new MediaFolderEntity();
        entity.setId(UUID.randomUUID());
        entity.setSiteId(UUID.randomUUID());
        entity.setParentId(parentId);
        entity.setPath(path);
        entity.setName(name);
        entity.setSortOrder(sortOrder);
        return entity;
    }
}
