package com.powercms.cms.audit;

import com.powercms.cms.audit.repo.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 审计事件保留清理（矩阵 I1，FR-AUDIT-001~003）：
 * <ul>
 *   <li>社区版保留窗口固定为 {@value #COMMUNITY_DAYS} 天，配置只能缩短，不能加长；</li>
 *   <li>分批删除（每批 ≤5000 条），避免大表长事务；</li>
 *   <li>清理由系统调度执行——「审计追加写、管理员不可修改或删除」的口径不受影响，
 *       管理面没有任何删除审计的入口；</li>
 *   <li>{@code auth-failure-retention-days=180} 属认证失败日志配置，与本任务相互独立。</li>
 * </ul>
 */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    /** 单批删除上限，沿用日志保留任务口径。 */
    static final int BATCH_SIZE = 5000;
    /** 社区版保留天数上限（规格 I1：社区建议 30 天）。 */
    static final int COMMUNITY_DAYS = 30;

    private final AuditEventRepository repo;
    /** {@code powercms.audit.retention-days}：0=按社区默认；>0 时只能缩短。 */
    private final int configuredRetentionDays;

    public AuditRetentionJob(AuditEventRepository repo,
                             @Value("${powercms.audit.retention-days:0}") int configuredRetentionDays) {
        this.repo = repo;
        this.configuredRetentionDays = configuredRetentionDays;
    }

    /**
     * 每日 03:40（cron 可经 {@code powercms.audit.retention-cron} 覆盖，避开 03:00 日志任务）。
     *
     * <p>每批独立事务（{@link AuditEventRepository#deleteByIdInBatch} 方法级 {@code @Transactional}）：
     * 本方法不再包外层事务，避免把所有批次塞进一个大表长事务；中断后重跑幂等（过期行仍在则继续删）。</p>
     */
    @Scheduled(cron = "${powercms.audit.retention-cron:0 40 3 * * *}")
    public void purgeExpired() {
        long start = System.nanoTime();
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays()));
            long removed = 0;
            List<UUID> batch;
            do {
                batch = repo.findExpiredIds(cutoff, PageRequest.of(0, BATCH_SIZE));
                if (batch.isEmpty()) break;
                removed += repo.deleteByIdInBatch(batch);
            } while (batch.size() >= BATCH_SIZE);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.info("audit retention done removed={} retentionDays={} cutoff={} elapsedMs={}",
                removed, retentionDays(), cutoff, elapsedMs);
        } catch (RuntimeException e) {
            log.error("audit retention failed: {}", e.getMessage());
        }
    }

    /**
     * 矩阵 I1：社区版有效保留天数。
     * 默认 {@value #COMMUNITY_DAYS} 天；配置只能在上限内缩短。
     */
    int retentionDays() {
        return configuredRetentionDays > 0
            ? Math.min(configuredRetentionDays, COMMUNITY_DAYS)
            : COMMUNITY_DAYS;
    }
}
