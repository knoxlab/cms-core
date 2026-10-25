package com.powercms.cms.systemlog;

import com.powercms.cms.systemlog.entity.AccessLogEntity;
import com.powercms.cms.systemlog.entity.RuntimeLogEntity;
import com.powercms.cms.systemlog.repo.AccessLogRepository;
import com.powercms.cms.systemlog.repo.RuntimeLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-LOG-ST-003/006 验收：日志保留清理必须真正落库执行——
 * <ul>
 *   <li>分区口径：INFO+ 按 runtimeRetentionDays、DEBUG/TRACE 按 debugRetentionDays、
 *       访问日志常规按 accessRetentionDays、AUTH_FAILURE/RATE_LIMITED 按 authFailureRetentionDays；</li>
 *   <li>未到期行在各分区均保留（cutoff 不越界）；</li>
 *   <li>任务以生产形态调用（方法上无外层事务）——删除语句必须自带事务边界，
 *       否则 @Modifying 抛 TransactionRequiredException 被任务 catch 吞掉、清理静默失效。</li>
 * </ul>
 *
 * <p>过期基线取 1920 年：早于所有保留周期（最长 180 天），保证只有本用例插入的
 * 行会被判定为过期；本用例行在 @AfterEach 兜底清理，不污染共享 H2 上下文。
 * fileLogDir 默认空串，文件通道扫描自动跳过。</p>
 */
@SpringBootTest
class SystemLogRetentionJobIntegrationTest {

    /** 早于全部保留周期（最长 180 天）→ 所有过期分区都命中。 */
    private static final Instant OLD = Instant.parse("1920-01-01T00:00:00Z");
    /** 当前时间 → 任何保留周期下都未到期。 */
    private static final Instant FRESH = Instant.now();

    private static final String MARK = "retention-it-";

    @Autowired
    private SystemLogRetentionJob job;

    @Autowired
    private RuntimeLogRepository runtimeRepo;

    @Autowired
    private AccessLogRepository accessRepo;

    private final List<UUID> runtimeIds = new ArrayList<>();
    private final List<UUID> accessIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        runtimeIds.forEach(runtimeRepo::deleteById);
        accessIds.forEach(accessRepo::deleteById);
        runtimeIds.clear();
        accessIds.clear();
    }

    @Test
    void purgeRemovesExpiredRowsPerPartitionAndKeepsFreshOnes() {
        // 运行日志：旧 INFO（标准分区过期）、旧 DEBUG（短保留分区过期）、新 WARN（保留）
        UUID runtimeInfoOld = saveRuntime(OLD, RuntimeLogEntity.LEVEL_INFO, MARK + "info-old");
        UUID runtimeDebugOld = saveRuntime(OLD, RuntimeLogEntity.LEVEL_DEBUG, MARK + "debug-old");
        UUID runtimeWarnFresh = saveRuntime(FRESH, RuntimeLogEntity.LEVEL_WARN, MARK + "warn-fresh");

        // 访问日志：旧常规（90 天分区过期）、旧认证失败/限流（180 天分区过期）、新常规/新认证（保留）
        UUID accessSuccessOld = saveAccess(OLD, AccessLogEntity.OUTCOME_SUCCESS, MARK + "success-old");
        UUID accessAuthOld = saveAccess(OLD, AccessLogEntity.OUTCOME_AUTH_FAILURE, MARK + "auth-old");
        UUID accessRateOld = saveAccess(OLD, AccessLogEntity.OUTCOME_RATE_LIMITED, MARK + "rate-old");
        UUID accessSuccessFresh = saveAccess(FRESH, AccessLogEntity.OUTCOME_SUCCESS, MARK + "success-fresh");
        UUID accessAuthFresh = saveAccess(FRESH, AccessLogEntity.OUTCOME_AUTH_FAILURE, MARK + "auth-fresh");

        job.purgeExpired();

        // 过期行按分区删除（若任务缺事务边界，行会残留 → 断言失败即暴露）
        assertThat(runtimeRepo.findById(runtimeInfoOld)).isEmpty();
        assertThat(runtimeRepo.findById(runtimeDebugOld)).isEmpty();
        assertThat(accessRepo.findById(accessSuccessOld)).isEmpty();
        assertThat(accessRepo.findById(accessAuthOld)).isEmpty();
        assertThat(accessRepo.findById(accessRateOld)).isEmpty();

        // 未到期行在所有分区保留
        assertThat(runtimeRepo.findById(runtimeWarnFresh)).isPresent();
        assertThat(accessRepo.findById(accessSuccessFresh)).isPresent();
        assertThat(accessRepo.findById(accessAuthFresh)).isPresent();
    }

    private UUID saveRuntime(Instant occurredAt, String level, String message) {
        RuntimeLogEntity e = RuntimeLogEntity.create(
            occurredAt, level, "test-service", "test-module", null, null, null, null,
            message, null, null, false, null);
        runtimeRepo.save(e);
        runtimeIds.add(e.getId());
        return e.getId();
    }

    private UUID saveAccess(Instant occurredAt, String outcome, String path) {
        AccessLogEntity e = AccessLogEntity.create(
            occurredAt, "127.0.0.1", "GET", path, (short) 200, 1, null,
            "tester", null, null, null, outcome, null);
        accessRepo.save(e);
        accessIds.add(e.getId());
        return e.getId();
    }
}
