package com.powercms.cms.content;

import com.powercms.cms.content.entity.CategoryEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * FR-AGT-SITE-001：站点范围内栏目读写与破坏性影响评估口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryServiceColumnTest {

    @Mock CategoryRepository catRepo;
    @Mock ContentItemRepository itemRepo;

    private CategoryService service;
    private final UUID siteId = UUID.randomUUID();
    private final UUID root = UUID.randomUUID();
    private final UUID child = UUID.randomUUID();
    private final UUID grandChild = UUID.randomUUID();
    private final UUID foreignSite = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CategoryService(catRepo, itemRepo);
        CategoryEntity r = cat(root, siteId, null, "新闻", "news", 0, 0);
        CategoryEntity c = cat(child, siteId, root, "技术", "tech", 1, 1);
        CategoryEntity g = cat(grandChild, siteId, child, "框架", "framework", 2, 2);
        Map<UUID, CategoryEntity> all = Map.of(root, r, child, c, grandChild, g);
        when(catRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(all.get(inv.getArgument(0))));
        when(catRepo.findBySiteId(siteId)).thenReturn(List.of(r, c, g));
        when(catRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.countByCategoryIdIn(anyCollection())).thenReturn(42L);
    }

    @Test
    void assessImpactCountsDescendantsAndContents() {
        CategoryService.ColumnImpact impact = service.assessImpact(siteId, root, true, child, "news-v2");

        assertThat(impact.descendantColumns()).isEqualTo(2);
        assertThat(impact.contentsAffected()).isEqualTo(42L);
        assertThat(impact.destructive()).isTrue();
        assertThat(impact.summary()).contains("子栏目 2 个").contains("关联内容 42 条");
    }

    @Test
    void renameOnlyIsNotDestructive() {
        CategoryService.ColumnImpact impact = service.assessImpact(siteId, root, false, null, null);

        assertThat(impact.destructive()).isFalse();
    }

    @Test
    void crossSiteColumnIsInvisible() {
        CategoryEntity foreign = cat(UUID.randomUUID(), foreignSite, null, "外部", "ext", 0, 0);
        when(catRepo.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getInSite(siteId, foreign.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("栏目不存在");
    }

    @Test
    void createRejectsParentFromAnotherSite() {
        UUID foreignParent = UUID.randomUUID();
        CategoryEntity foreign = cat(foreignParent, foreignSite, null, "外部", "ext", 0, 0);
        when(catRepo.findById(foreignParent)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.createInSite(siteId, foreignParent, "子栏目", "sub", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("栏目不存在");
    }

    @Test
    void updateRejectsMoveIntoOwnDescendant() {
        assertThatThrownBy(() -> service.updateInSite(siteId, root, true, grandChild, null, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("自身后代");
    }

    @Test
    void updateCanMoveToRootExplicitly() {
        CategoryService.CategoryInfo moved = service.updateInSite(siteId, child, true, null, null, null, null);

        assertThat(moved.parentId()).isNull();
        assertThat(moved.depth()).isZero();
    }

    private static CategoryEntity cat(UUID id, UUID siteId, UUID parentId, String name, String alias,
                                      int sortOrder, int depth) {
        CategoryEntity e = new CategoryEntity();
        e.setId(id);
        e.setSiteId(siteId);
        e.setParentId(parentId);
        e.setName(name);
        e.setPathAlias(alias);
        e.setSortOrder(sortOrder);
        e.setDepth(depth);
        return e;
    }
}
