package com.powercms.cms.search;

import com.powercms.cms.publish.ReleaseDeployRequestedEvent;
import com.powercms.cms.publish.ReleaseService;
import com.powercms.cms.search.entity.SiteIndexStatusEntity;
import com.powercms.cms.search.repo.SiteIndexStatusRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-SEARCH-007 索引落后门禁：**先按目标 Release 自愈建立访客索引，成功即放行；失败才回退**。
 *
 * <p>口径统一带来的行为变化：门禁不再只做校验——因为访客索引的唯一合法人群是「已部署 Release 成员」，
 * 而部署前该 Release 还不是 DEPLOYED，只校验会让**首次部署**永远被挡（鸡生蛋）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexSyncGateTest {

    @Mock SiteIndexStatusRepository statusRepo;
    @Mock ReleaseService releaseService;
    @Mock PublicSearchService publicSearchService;

    private IndexSyncGate gate;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        gate = new IndexSyncGate(statusRepo, releaseService, publicSearchService, true);
        siteId = UUID.randomUUID();
        when(statusRepo.findById(any())).thenReturn(Optional.empty());
    }

    /** 首次部署（索引从未登记过）：自愈建索引 → 登记一致 → 放行，不调 undeploy。 */
    @Test
    void selfHealsAndAllowsDeployWhenIndexWasNeverBuilt() {
        UUID release = UUID.randomUUID();
        // 自愈重建会登记 last_release_id（模拟 rebuildForRelease 的副作用）
        org.mockito.Mockito.doAnswer(inv -> {
            when(statusRepo.findById(siteId)).thenReturn(Optional.of(
                SiteIndexStatusEntity.of(siteId, "alias", release, 2)));
            return null;
        }).when(publicSearchService).rebuildForRelease(siteId, release);

        assertThatCode(() -> gate.onDeployRequested(new ReleaseDeployRequestedEvent(release, siteId)))
            .doesNotThrowAnyException();
        verify(publicSearchService).rebuildForRelease(siteId, release);
        verify(releaseService, never()).undeploy(any());
    }

    /** 索引落后于目标 Release：先尝试自愈；自愈后仍不一致 → 回退部署并抛契约错误。 */
    @Test
    void blocksDeployWhenSelfHealDoesNotConverge() {
        UUID staleRelease = UUID.randomUUID();
        when(statusRepo.findById(siteId)).thenReturn(Optional.of(
            SiteIndexStatusEntity.of(siteId, "alias", staleRelease, 5)));
        var event = new ReleaseDeployRequestedEvent(UUID.randomUUID(), siteId);

        assertThatThrownBy(() -> gate.onDeployRequested(event))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("门禁阻断");
        verify(publicSearchService).rebuildForRelease(siteId, event.releaseId());
        verify(releaseService).undeploy(event.releaseId());
    }

    /** 自愈本身失败（搜索后端不可用）：不得静默放行——回退部署并说明自愈原因。 */
    @Test
    void blocksDeployWhenSelfHealThrows() {
        UUID release = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new SearchUnavailableException("opensearch down"))
            .when(publicSearchService).rebuildForRelease(siteId, release);

        assertThatThrownBy(() -> gate.onDeployRequested(new ReleaseDeployRequestedEvent(release, siteId)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("访客索引自愈失败");
        verify(releaseService).undeploy(release);
    }

    @Test
    void allowsDeployWhenIndexMatchesRelease() {
        UUID release = UUID.randomUUID();
        when(statusRepo.findById(siteId)).thenReturn(Optional.of(
            SiteIndexStatusEntity.of(siteId, "alias", release, 10)));

        assertThatCode(() -> gate.onDeployRequested(new ReleaseDeployRequestedEvent(release, siteId)))
            .doesNotThrowAnyException();
        verify(releaseService, never()).undeploy(any());
        verify(publicSearchService, never()).rebuildForRelease(any(), any());
    }

    @Test
    void disabledGateDoesNothing() {
        var permissive = new IndexSyncGate(statusRepo, releaseService, publicSearchService, false);
        assertThatCode(() -> permissive.onDeployRequested(
                new ReleaseDeployRequestedEvent(UUID.randomUUID(), siteId)))
            .doesNotThrowAnyException();
        verify(releaseService, never()).undeploy(any());
        verify(publicSearchService, never()).rebuildForRelease(any(), any());
    }

    @Test
    void recordRebuildInsertsThenUpdates() {
        UUID releaseA = UUID.randomUUID();
        UUID releaseB = UUID.randomUUID();

        gate.recordRebuild(siteId, "alias-a", releaseA, 3);
        verify(statusRepo).save(any(SiteIndexStatusEntity.class));

        var existing = SiteIndexStatusEntity.of(siteId, "alias-a", releaseA, 3);
        when(statusRepo.findById(siteId)).thenReturn(Optional.of(existing));
        gate.recordRebuild(siteId, "alias-b", releaseB, 9);
        org.assertj.core.api.Assertions.assertThat(existing.getLastReleaseId()).isEqualTo(releaseB);
        org.assertj.core.api.Assertions.assertThat(existing.getDocumentCount()).isEqualTo(9L);
        org.assertj.core.api.Assertions.assertThat(existing.getIndexAlias()).isEqualTo("alias-b");
    }

    @Test
    void statusReportsInSyncOnlyWithDeployedRelease() {
        UUID deployed = UUID.randomUUID();
        when(releaseService.getDeployed(siteId)).thenReturn(new ReleaseService.ReleaseInfo(
            deployed, siteId, "r", null, "DEPLOYED", null, null, java.util.List.of()));
        when(statusRepo.findById(siteId)).thenReturn(Optional.of(
            SiteIndexStatusEntity.of(siteId, "alias", deployed, 4)));

        assertThat(gate.status(siteId).inSync()).isTrue();
        verify(releaseService).getDeployed(eq(siteId));
    }
}
