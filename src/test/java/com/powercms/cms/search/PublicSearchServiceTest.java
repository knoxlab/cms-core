package com.powercms.cms.search;

import com.powercms.cms.content.ContentPublishedEvent;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.publish.ReleaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访客索引口径统一（FR-SEARCH-002 / FR-API-002 / FR-SEARCH-007）：
 * 人群 = 已部署 Release 成员 ∩ PUBLISHED；文档 = 指定成员版本 + releaseId；
 * 无部署时重建为空索引、查询直接空集；发布事件只刷新「已部署 Release 内的内容」。
 */
class PublicSearchServiceTest {
    private final ReleaseService releases = mock(ReleaseService.class);
    private final ContentItemRepository items = mock(ContentItemRepository.class);
    private final SearchIndexStore store = mock(SearchIndexStore.class);
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<IndexSyncGate> gate =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    private final AdminSearchService adminSearchService = mock(AdminSearchService.class);
    private final ObjectPermissionService objectPermissions = mock(ObjectPermissionService.class);
    private final PublicIndexScope scope = new PublicIndexScope(releases, items);
    private final PublicSearchService service = new PublicSearchService(items, store, "test", gate,
        objectPermissions, adminSearchService, scope);

    private ContentItemEntity item(UUID siteId, UUID id, String status) {
        ContentItemEntity e = new ContentItemEntity();
        e.setId(id);
        e.setSiteId(siteId);
        e.setStatus(status);
        e.setTitle("title");
        return e;
    }

    private ReleaseService.ReleaseInfo release(UUID releaseId, UUID siteId,
                                               ReleaseService.ReleaseContentInfo... members) {
        return new ReleaseService.ReleaseInfo(releaseId, siteId, "r", null, "DEPLOYED",
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), List.of(members));
    }

    private String alias(UUID siteId) {
        return "powercms-public-test-" + siteId.toString().replace("-", "");
    }

    private ContentPublishedEvent published(UUID siteId, UUID contentId, int version) {
        return new ContentPublishedEvent(UUID.randomUUID(), siteId, contentId, UUID.randomUUID(),
            "/x", version, "DRAFT");
    }

    /**
     * 回归（口径统一的动因）：Release 成员即使条目本体已被撤回，也必须按**成员版本**写入，
     * 且文档带 status / releaseId / contentVersion ——此前该路径的文档缺 {@code status}，
     * 与查询期 {@code status=PUBLISHED} 过滤互相打架（重建后匿名检索全空）。
     */
    @Test
    void rebuildUsesFrozenMembershipVersionAndWritesReleaseScopedDocument() {
        UUID siteId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        ContentItemEntity item = item(siteId, contentId, ContentItemEntity.STATUS_PUBLISHED);
        when(releases.getDeployed(siteId)).thenReturn(release(releaseId, siteId,
            new ReleaseService.ReleaseContentInfo(contentId, 3)));
        when(items.findById(contentId)).thenReturn(Optional.of(item));
        when(adminSearchService.buildDocument(eq(item), eq(3),
            eq(com.powercms.cms.content.ContentSearchTextAssembler.Target.PUBLIC), eq(releaseId)))
            .thenReturn(new SearchIndexStore.SearchDocument(contentId.toString(),
                java.util.Map.of("siteId", siteId.toString(), "status", "PUBLISHED",
                    "releaseId", releaseId.toString(), "contentVersion", 3)));

        service.rebuild(siteId);

        verify(adminSearchService).buildDocument(eq(item), eq(3),
            eq(com.powercms.cms.content.ContentSearchTextAssembler.Target.PUBLIC), eq(releaseId));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SearchIndexStore.SearchDocument>> documents = ArgumentCaptor.forClass(List.class);
        verify(store).rebuild(eq(alias(siteId)), startsWith("powercms-public-test-"),
            documents.capture(), nullable(SearchIndexStore.IndexMapping.class));
        assertThat(documents.getValue()).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo(contentId.toString());
            assertThat(document.fields())
                .containsEntry("status", "PUBLISHED")
                .containsEntry("releaseId", releaseId.toString())
                .containsEntry("contentVersion", 3);
        });
    }

    /** 无已部署 Release：重建出**空**访客索引（规格口径），不得回退为「全站发布态」。 */
    @Test
    void rebuildWithoutDeployedReleaseProducesEmptyIndex() {
        UUID siteId = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(null);

        service.rebuild(siteId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SearchIndexStore.SearchDocument>> documents = ArgumentCaptor.forClass(List.class);
        verify(store).rebuild(eq(alias(siteId)), startsWith("powercms-public-test-"),
            documents.capture(), nullable(SearchIndexStore.IndexMapping.class));
        assertThat(documents.getValue()).isEmpty();
        verify(adminSearchService, never()).buildDocument(any(), anyInt(), any(), any());
    }

    /** FR-SEARCH-007 门禁自愈：按**尚未部署**的指定 Release 建索引（首次部署的唯一可用时机）。 */
    @Test
    void rebuildForReleaseUsesExplicitReleaseInsteadOfDeployedOne() {
        UUID siteId = UUID.randomUUID();
        UUID pendingRelease = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        ContentItemEntity item = item(siteId, contentId, ContentItemEntity.STATUS_PUBLISHED);
        when(releases.get(pendingRelease)).thenReturn(release(pendingRelease, siteId,
            new ReleaseService.ReleaseContentInfo(contentId, 9)));
        when(items.findById(contentId)).thenReturn(Optional.of(item));
        when(adminSearchService.buildDocument(eq(item), eq(9), any(), eq(pendingRelease)))
            .thenReturn(new SearchIndexStore.SearchDocument(contentId.toString(),
                java.util.Map.of("siteId", siteId.toString(), "status", "PUBLISHED")));

        service.rebuildForRelease(siteId, pendingRelease);

        verify(adminSearchService).buildDocument(eq(item), eq(9), any(), eq(pendingRelease));
        verify(releases, never()).getDeployed(any());
    }

    /** 无已部署 Release：访客检索直接空集（与交付读取同口径），且不触碰索引。 */
    @Test
    void searchReturnsEmptyWhenNothingIsDeployed() {
        UUID siteId = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(null);

        assertThat(service.search(siteId, "term")).isEmpty();
        verify(store, never()).search(anyString(), anyString(), anyMap(), anyInt());
    }

    /** 有部署：检索仍叠加 siteId + status 过滤，保持纵深防御。 */
    @Test
    void searchAppliesSiteAndStatusFiltersWhenDeployed() {
        UUID siteId = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(release(UUID.randomUUID(), siteId));
        when(store.search(anyString(), anyString(), anyMap(), anyInt())).thenReturn(List.of());

        service.search(siteId, "term");

        verify(store).search(alias(siteId), "term",
            java.util.Map.of("siteId", siteId.toString(), "status", "PUBLISHED"), 200);
    }

    /** 发布增量：内容在已部署 Release 内 → 按**成员版本**刷新访客索引文档。 */
    @Test
    void onPublishedRefreshesOnlyMembersOfDeployedReleaseWithPinnedVersion() {
        UUID siteId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        ContentItemEntity item = item(siteId, contentId, ContentItemEntity.STATUS_PUBLISHED);
        when(releases.getDeployed(siteId)).thenReturn(release(releaseId, siteId,
            new ReleaseService.ReleaseContentInfo(contentId, 4)));
        when(items.findById(contentId)).thenReturn(Optional.of(item));
        when(adminSearchService.buildDocument(eq(item), eq(4),
            eq(com.powercms.cms.content.ContentSearchTextAssembler.Target.PUBLIC), eq(releaseId)))
            .thenReturn(new SearchIndexStore.SearchDocument(contentId.toString(),
                java.util.Map.of("siteId", siteId.toString(), "status", "PUBLISHED")));

        service.onPublished(published(siteId, contentId, 4));

        verify(store).upsert(eq(alias(siteId)), any(SearchIndexStore.SearchDocument.class));
        verify(adminSearchService).buildDocument(eq(item), eq(4), any(), eq(releaseId));
    }

    /** 发布增量：内容不在已部署 Release 内 → **不写**访客索引（否则索引 ⊋ 交付可见集）。 */
    @Test
    void onPublishedSkipsContentOutsideDeployedRelease() {
        UUID siteId = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(release(UUID.randomUUID(), siteId));

        service.onPublished(published(siteId, outsider, 1));

        verify(store, never()).upsert(anyString(), any());
        verify(adminSearchService, never()).buildDocument(any(), anyInt(), any(), any());
    }

    /** 发布增量：站点**无已部署 Release** → 一律不写访客索引。 */
    @Test
    void onPublishedSkipsEverythingWhenNoReleaseDeployed() {
        UUID siteId = UUID.randomUUID();
        when(releases.getDeployed(siteId)).thenReturn(null);

        service.onPublished(published(siteId, UUID.randomUUID(), 1));

        verify(store, never()).upsert(anyString(), any());
    }
}
