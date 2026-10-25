package com.powercms.cms.search;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.publish.ReleaseService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访客索引人群唯一真源（FR-SEARCH-002 / FR-API-002）：与 {@code DeliveryService.listPublished}
 * 同谓词——已部署 Release 成员 ∩ PUBLISHED ∩ 同站点；**无已部署 Release 即空集**。
 */
class PublicIndexScopeTest {

    private final ReleaseService releases = mock(ReleaseService.class);
    private final ContentItemRepository items = mock(ContentItemRepository.class);
    private final PublicIndexScope scope = new PublicIndexScope(releases, items);

    private static ContentItemEntity item(UUID siteId, UUID id, String status) {
        ContentItemEntity e = new ContentItemEntity();
        e.setId(id);
        e.setSiteId(siteId);
        e.setStatus(status);
        e.setTitle("t");
        return e;
    }

    private static ReleaseService.ReleaseInfo release(UUID releaseId, UUID siteId,
                                                     ReleaseService.ReleaseContentInfo... members) {
        return new ReleaseService.ReleaseInfo(releaseId, siteId, "r", null, "DEPLOYED",
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), List.of(members));
    }

    @Test
    void noDeployedReleaseYieldsEmptyScope() {
        UUID siteId = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(null);

        PublicIndexScope.Scope deployed = scope.deployed(siteId);

        assertThat(deployed.releaseId()).isNull();
        assertThat(deployed.isEmpty()).isTrue();
        verify(items, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deployedScopeKeepsOnlyPublishedMembersOfThatSite() {
        UUID siteId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        UUID otherSite = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(release(releaseId, siteId,
            new ReleaseService.ReleaseContentInfo(published, 4),
            new ReleaseService.ReleaseContentInfo(draft, 2),
            new ReleaseService.ReleaseContentInfo(otherSite, 1)));
        when(items.findById(published))
            .thenReturn(Optional.of(item(siteId, published, ContentItemEntity.STATUS_PUBLISHED)));
        // Release 冻结之后被撤回/降级为草稿的成员不再对外可见（与交付读取同谓词）
        when(items.findById(draft))
            .thenReturn(Optional.of(item(siteId, draft, ContentItemEntity.STATUS_DRAFT)));
        // 跨站点成员一律剔除（Release 与站点不一致的脏数据不得进人群）
        when(items.findById(otherSite))
            .thenReturn(Optional.of(item(UUID.randomUUID(), otherSite, ContentItemEntity.STATUS_PUBLISHED)));

        PublicIndexScope.Scope deployed = scope.deployed(siteId);

        assertThat(deployed.releaseId()).isEqualTo(releaseId);
        assertThat(deployed.entries()).containsExactly(new PublicIndexScope.Entry(published, 4));
    }

    @Test
    void entriesAreSortedByContentIdForStableCursor() {
        UUID siteId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        // 故意乱序：成员清单顺序由 Release 冻结顺序决定，与游标序无关
        List<UUID> ids = List.of(UUID.fromString("00000000-0000-0000-0000-0000000000cc"),
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            UUID.fromString("00000000-0000-0000-0000-0000000000bb"));
        when(releases.getDeployed(siteId)).thenReturn(release(releaseId, siteId,
            new ReleaseService.ReleaseContentInfo(ids.get(0), 1),
            new ReleaseService.ReleaseContentInfo(ids.get(1), 1),
            new ReleaseService.ReleaseContentInfo(ids.get(2), 1)));
        ids.forEach(id -> when(items.findById(id))
            .thenReturn(Optional.of(item(siteId, id, ContentItemEntity.STATUS_PUBLISHED))));

        PublicIndexScope.Scope deployed = scope.deployed(siteId);

        assertThat(deployed.entries().stream().map(PublicIndexScope.Entry::contentItemId))
            .containsExactly(ids.get(1), ids.get(2), ids.get(0));
    }

    @Test
    void deletedMemberIsSkipped() {
        UUID siteId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(release(releaseId, siteId,
            new ReleaseService.ReleaseContentInfo(gone, 1)));
        when(items.findById(gone)).thenReturn(Optional.empty());

        assertThat(scope.deployed(siteId).isEmpty()).isTrue();
    }

    @Test
    void forReleaseRejectsReleaseOfAnotherSiteAndUnknownRelease() {
        UUID siteId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        when(releases.get(releaseId)).thenReturn(release(releaseId, UUID.randomUUID()));

        assertThat(scope.forRelease(siteId, releaseId).isEmpty()).isTrue();

        UUID missing = UUID.randomUUID();
        when(releases.get(missing)).thenThrow(new IllegalArgumentException("not found"));
        assertThat(scope.forRelease(siteId, missing).isEmpty()).isTrue();
        assertThat(scope.forRelease(siteId, null).isEmpty()).isTrue();
    }

    @Test
    void forReleaseUsesExplicitReleaseBeforeDeployment() {
        // FR-SEARCH-007 门禁自愈：**尚未部署**的 Release 也必须能按它建立候选访客索引（首次部署鸡生蛋）
        UUID siteId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        when(releases.get(releaseId)).thenReturn(release(releaseId, siteId,
            new ReleaseService.ReleaseContentInfo(contentId, 7)));
        when(items.findById(contentId))
            .thenReturn(Optional.of(item(siteId, contentId, ContentItemEntity.STATUS_PUBLISHED)));

        PublicIndexScope.Scope explicit = scope.forRelease(siteId, releaseId);

        assertThat(explicit.releaseId()).isEqualTo(releaseId);
        assertThat(explicit.entries()).containsExactly(new PublicIndexScope.Entry(contentId, 7));
        verify(releases, never()).getDeployed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deployedEntryGatesPublishEventByMembershipAndPinsVersion() {
        UUID siteId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(release(releaseId, siteId,
            new ReleaseService.ReleaseContentInfo(member, 5)));

        assertThat(scope.deployedEntry(siteId, member))
            .contains(new PublicIndexScope.Entry(member, 5));
        // 不在已部署 Release 内的内容：发布事件不得写访客索引（交付也拿不到）
        assertThat(scope.deployedEntry(siteId, outsider)).isEmpty();
        assertThat(scope.deployedEntry(siteId, null)).isEmpty();
        assertThat(scope.deployedReleaseId(siteId)).isEqualTo(releaseId);
    }
}
