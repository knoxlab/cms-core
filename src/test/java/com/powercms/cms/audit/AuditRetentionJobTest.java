package com.powercms.cms.audit;

import com.powercms.cms.audit.repo.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计事件保留期限（矩阵 I1，社区版口径）。
 *
 * <p>社区版保留窗口固定 {@value AuditRetentionJob#COMMUNITY_DAYS} 天，配置只能缩短；
 * 清理经调度分批执行，管理面无删除审计入口（FR-AUDIT-001 口径不变）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditRetentionJobTest {

    @Mock AuditEventRepository repo;

    private AuditRetentionJob job(int configuredDays) {
        return new AuditRetentionJob(repo, configuredDays);
    }

    @Test
    void 未配置保留30天() {
        assertThat(job(0).retentionDays()).isEqualTo(30);
    }

    @Test
    void 配置365天_被上限收敛到30天() {
        assertThat(job(365).retentionDays()).isEqualTo(30);
    }

    @Test
    void 配置7天_可缩短() {
        assertThat(job(7).retentionDays()).isEqualTo(7);
    }

    // ---------- 调度清理 ----------

    @Test
    void 清理按cutoff分批执行到收敛() {
        List<UUID> fullBatch = uuids(AuditRetentionJob.BATCH_SIZE);
        List<UUID> tailBatch = uuids(12);
        lenient().when(repo.findExpiredIds(any(Instant.class), any(PageRequest.class)))
            .thenReturn(fullBatch, tailBatch);
        lenient().when(repo.deleteByIdInBatch(any()))
            .thenReturn(AuditRetentionJob.BATCH_SIZE, 12);
        job(0).purgeExpired();
        // 取号两次（整批 + 尾批），尾批不足整批即收敛；cutoff 落在 29~31 天前（社区 30 天）
        verify(repo, times(2)).findExpiredIds(argThat(
            cutoff -> cutoff.isBefore(Instant.now().minus(29, ChronoUnit.DAYS))
                && cutoff.isAfter(Instant.now().minus(31, ChronoUnit.DAYS))), any(PageRequest.class));
        verify(repo, times(2)).deleteByIdInBatch(any());
    }

    @Test
    void 无过期数据时单次探测即结束() {
        when(repo.findExpiredIds(any(Instant.class), any(PageRequest.class))).thenReturn(List.of());
        job(0).purgeExpired();
        verify(repo, times(1)).findExpiredIds(any(Instant.class), any(PageRequest.class));
        verify(repo, never()).deleteByIdInBatch(any());
    }

    private static List<UUID> uuids(int n) {
        return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }
}
