package com.powercms.cms.systemlog;

import com.powercms.cms.systemlog.repo.AccessLogRepository;
import com.powercms.cms.systemlog.repo.RuntimeLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 日志保留清理任务（FR-LOG-ST-005/006，SystemLogRetentionJob，每日 03:00）：
 * <ul>
 *   <li>按保留周期删除过期条目：运行日志 INFO+ 30 天 / DEBUG+ 7 天、访问日志 90 天、
 *       认证失败与限流 180 天（FR-LOG-ST-003，均可配置）；</li>
 *   <li>分批执行（每批 ≤ 5000 条，FR-LOG-ST-006），避免长事务与大表锁；</li>
 *   <li>执行结果（删除条数、耗时）以 INFO 写入运行日志（经文件通道，避开本任务自身落库回环）；</li>
 *   <li>文件通道：删除过期滚动文件（powercms.YYYY-MM-DD.log.gz），保留 180 天（FR-LOG-ST-004）；</li>
 *   <li>连续 3 天失败产生 WARN 告警（NFR-LOG-012），次日自动重试。</li>
 * </ul>
 */
@Component
public class SystemLogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SystemLogRetentionJob.class);
    /** 单批删除上限（FR-LOG-ST-006）。 */
    static final int BATCH_SIZE = 5000;
    /** 连续失败告警阈值（NFR-LOG-012）。 */
    private static final int FAILURE_ALERT_THRESHOLD = 3;

    private final RuntimeLogRepository runtimeRepo;
    private final AccessLogRepository accessRepo;
    private final SystemLogProperties props;
    private volatile int consecutiveFailures = 0;

    public SystemLogRetentionJob(RuntimeLogRepository runtimeRepo,
                                 AccessLogRepository accessRepo,
                                 SystemLogProperties props) {
        this.runtimeRepo = runtimeRepo;
        this.accessRepo = accessRepo;
        this.props = props;
    }

    /** 每日 03:00（FR-LOG-ST-005；沿用既有定时任务惯例，cron 可经配置覆盖）。 */
    @Scheduled(cron = "${powercms.system-log.retention-cron:0 0 3 * * *}")
    public void purgeExpired() {
        long start = System.nanoTime();
        try {
            long runtime = purgeRuntime();
            long access = purgeAccess();
            long files = purgeFiles();
            consecutiveFailures = 0;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.info("system log retention done runtimeRemoved={} accessRemoved={} filesRemoved={} elapsedMs={}",
                runtime, access, files, elapsedMs);
        } catch (RuntimeException e) {
            consecutiveFailures++;
            if (consecutiveFailures >= FAILURE_ALERT_THRESHOLD) {
                log.warn("system log retention failed {} consecutive days: {}",
                    consecutiveFailures, e.getMessage());
            } else {
                log.error("system log retention failed (attempt {}): {}",
                    consecutiveFailures, e.getMessage());
            }
        }
    }

    /** 运行日志清理：INFO+ 按 runtimeRetentionDays，DEBUG/TRACE 按 debugRetentionDays（FR-LOG-ST-003）。 */
    private long purgeRuntime() {
        Instant infoCutoff = Instant.now().minus(Duration.ofDays(props.getRuntimeRetentionDays()));
        Instant debugCutoff = Instant.now().minus(Duration.ofDays(props.getDebugRetentionDays()));
        long removed = purgeInBatches(
            p -> runtimeRepo.findLowLevelExpiredIds(debugCutoff, p), runtimeRepo::deleteByIdInBatch);
        removed += purgeInBatches(
            p -> runtimeRepo.findStandardExpiredIds(infoCutoff, p), runtimeRepo::deleteByIdInBatch);
        return removed;
    }

    /** 访问日志清理：常规按 accessRetentionDays；认证失败/限流按 authFailureRetentionDays（FR-LOG-ST-003）。 */
    private long purgeAccess() {
        Instant normalCutoff = Instant.now().minus(Duration.ofDays(props.getAccessRetentionDays()));
        Instant securityCutoff = Instant.now().minus(Duration.ofDays(props.getAuthFailureRetentionDays()));
        long removed = purgeInBatches(
            p -> accessRepo.findSecurityExpiredIds(securityCutoff, p), accessRepo::deleteByIdInBatch);
        removed += purgeInBatches(
            p -> accessRepo.findNormalExpiredIds(normalCutoff, p), accessRepo::deleteByIdInBatch);
        return removed;
    }

    /**
     * 分批执行（FR-LOG-ST-006）：按主键取最旧一批（≤BATCH_SIZE）删除，取回批不足整批即收敛。
     *
     * <p>2026-10-19 验收修复：原实现把 batchSize 传给从未在 JPQL 中引用它的删除方法
     * （分批形同虚设），且 {@code @Modifying} 在本任务的无事务上下文执行直接抛
     * TransactionRequiredException——异常被 {@link #purgeExpired()} 的 catch 吞掉，
     * 该任务自上线起从未真正删除过任何行。现取号走 Pageable 分页（方言安全），
     * 删除走 {@code deleteByIdInBatch}（方法级事务，每批一个独立短事务）。</p>
     */
    private long purgeInBatches(Function<Pageable, List<UUID>> fetchExpiredIds,
                                Function<List<UUID>, Integer> deleteIds) {
        long removed = 0;
        List<UUID> batch;
        do {
            batch = fetchExpiredIds.apply(PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) break;
            removed += deleteIds.apply(batch);
        } while (batch.size() >= BATCH_SIZE);
        return removed;
    }

    /** 文件通道清理（FR-LOG-ST-004）：删除 fileLogDir 下超过 fileRetentionDays 的滚动文件。 */
    private long purgeFiles() {
        String dir = props.getFileLogDir();
        if (dir == null || dir.isBlank()) return 0;
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) return 0;
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.getFileRetentionDays()));
        long removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "powercms.*.log.gz")) {
            for (Path file : files) {
                try {
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    if (modified.isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // 单文件删除失败不影响其余清理
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("retention file scan failed: " + e.getMessage(), e);
        }
        return removed;
    }
}
