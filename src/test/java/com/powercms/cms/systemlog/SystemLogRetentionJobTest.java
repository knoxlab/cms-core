package com.powercms.cms.systemlog;

import com.powercms.cms.systemlog.repo.AccessLogRepository;
import com.powercms.cms.systemlog.repo.RuntimeLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-LOG-ST-006 分批机制回归（2026-10-19 验收修复）：
 * <ul>
 *   <li>取回整批（≥BATCH_SIZE）→ 继续下一批，直到不足整批收敛；</li>
 *   <li>取回空批 → 不执行任何删除；</li>
 *   <li>删除以主键列表为单位（每批一个独立事务，见各 Repository.deleteByIdInBatch）。</li>
 * </ul>
 *
 * <p>背景：原实现把 batchSize 传给从未引用它的 JPQL，且调用链无事务——
 * 任务自上线起从未成功删除过任何行；本组用例锁住修复后的分批语义。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemLogRetentionJobTest {

    @Mock
    private RuntimeLogRepository runtimeRepo;
    @Mock
    private AccessLogRepository accessRepo;

    private SystemLogRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new SystemLogRetentionJob(runtimeRepo, accessRepo, new SystemLogProperties());
        // 默认空表：各取号口只返回空批，具体用例按需覆盖
        when(runtimeRepo.findLowLevelExpiredIds(any(), any())).thenReturn(List.of());
        when(runtimeRepo.findStandardExpiredIds(any(), any())).thenReturn(List.of());
        when(accessRepo.findSecurityExpiredIds(any(), any())).thenReturn(List.of());
        when(accessRepo.findNormalExpiredIds(any(), any())).thenReturn(List.of());
    }

    @Test
    void 整批未取尽时继续下一批直到不足整批() {
        List<UUID> fullBatch = uuids(SystemLogRetentionJob.BATCH_SIZE);
        List<UUID> tailBatch = uuids(1);
        when(runtimeRepo.findStandardExpiredIds(any(), any())).thenReturn(fullBatch, tailBatch);
        when(runtimeRepo.deleteByIdInBatch(any()))
            .thenReturn(SystemLogRetentionJob.BATCH_SIZE, 1);

        job.purgeExpired();

        // 取号两次：整批 + 尾批；尾批不足整批即收敛，不再取第三批
        verify(runtimeRepo, times(2)).findStandardExpiredIds(any(), any());
        verify(runtimeRepo, times(2)).deleteByIdInBatch(any());
        verify(runtimeRepo, times(1)).deleteByIdInBatch(argThat(ids -> ids.size() == SystemLogRetentionJob.BATCH_SIZE));
        verify(runtimeRepo, times(1)).deleteByIdInBatch(argThat(ids -> ids.size() == 1));
    }

    @Test
    void 空表时只取号不执行删除() {
        job.purgeExpired();

        // 四个取号口各查一次（DEBUG/TRACE、标准级别、安全 outcome、常规 outcome）
        verify(runtimeRepo, times(1)).findLowLevelExpiredIds(any(), any());
        verify(runtimeRepo, times(1)).findStandardExpiredIds(any(), any());
        verify(accessRepo, times(1)).findSecurityExpiredIds(any(), any());
        verify(accessRepo, times(1)).findNormalExpiredIds(any(), any());
        verify(runtimeRepo, never()).deleteByIdInBatch(any());
        verify(accessRepo, never()).deleteByIdInBatch(any());
    }

    @Test
    void 访问日志安全分区与常规分区分别按各自保留周期取号() {
        job.purgeExpired();

        // 分区取号使用各自 cutoff：安全分区 authFailureRetentionDays=180、常规 accessRetentionDays=90
        var now = java.time.Instant.now();
        verify(accessRepo, times(1)).findSecurityExpiredIds(
            argThat(cutoff -> Math.abs(java.time.Duration.between(cutoff, now).toDays() - 180) <= 1), any());
        verify(accessRepo, times(1)).findNormalExpiredIds(
            argThat(cutoff -> Math.abs(java.time.Duration.between(cutoff, now).toDays() - 90) <= 1), any());
        // 运行日志：INFO+ 30 天、DEBUG/TRACE 7 天
        verify(runtimeRepo, times(1)).findStandardExpiredIds(
            argThat(cutoff -> Math.abs(java.time.Duration.between(cutoff, now).toDays() - 30) <= 1), any());
        verify(runtimeRepo, times(1)).findLowLevelExpiredIds(
            argThat(cutoff -> Math.abs(java.time.Duration.between(cutoff, now).toDays() - 7) <= 1), any());
    }

    private static List<UUID> uuids(int n) {
        return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }
}
