package com.powercms.cms.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 定时任务防重锁（FR-STAT-105）。同一锁名同一时刻仅一个执行方持有。
 *
 * <p>五库分工（能力位由 AnalyticsDialect 提供）：</p>
 * <ul>
 *   <li>PG 系（PostgreSQL / 金仓）：会话级咨询锁 pg_try_advisory_lock，跨节点互斥，
 *       持锁节点崩溃由会话结束自动释放；<b>函数缺失/不可用时运行期捕获并退化本地锁</b>
 *       （金仓扩展差异、权限不足等场景不致启动失败）；</li>
 *   <li>H2 / MySQL / 达梦：无咨询锁函数 → JVM 内 ReentrantLock（单机部署语义等价；
 *       多节点部署这些库时退化为各节点本地锁，跨节点防重不生效——见 README 验证矩阵）。</li>
 * </ul>
 *
 * <p>分片任务用锁名后缀区分维度（如站点/日期），保证同一 (站点, 日期) 不被并行处理。</p>
 */
@Service
public class AnalyticsJobLockService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsJobLockService.class);

    @PersistenceContext
    private EntityManager em;

    private final AnalyticsDialect dialect;
    private final long lockTimeoutSeconds;
    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public AnalyticsJobLockService(AnalyticsDialect dialect,
                                   @Value("${powercms.analytics.job-lock-timeout-seconds:300}") long lockTimeoutSeconds) {
        this.dialect = dialect;
        this.lockTimeoutSeconds = Math.max(30, lockTimeoutSeconds);
    }

    /**
     * 尝试获取锁并执行 body；未取到锁返回 null（其他节点在跑）。
     * PG 系咨询锁会话绑定当前连接，事务结束/连接关闭自动释放。
     */
    public <T> T tryRun(String lockName, Supplier<T> body) {
        return tryRun(lockName, Duration.ofSeconds(lockTimeoutSeconds), body);
    }

    public <T> T tryRun(String lockName, Duration timeout, Supplier<T> body) {
        if (dialect.supportsAdvisoryLocks()) {
            Boolean acquired = null;
            try {
                acquired = tryAcquire(lockName);
            } catch (RuntimeException e) {
                // 咨询锁函数缺失/不可用（厂商扩展差异、权限）→ 退化本地锁，不让任务失败
                log.debug("pg advisory lock unavailable, fallback to local lock: {}", e.getMessage());
            }
            if (Boolean.TRUE.equals(acquired)) {
                try {
                    return body.get();
                } finally {
                    release(lockName);
                }
            }
            if (Boolean.FALSE.equals(acquired)) {
                return null; // 其他节点持有锁
            }
            // acquired == null：函数不可用 → 落到本地锁分支
        }
        ReentrantLock lock = localLocks.computeIfAbsent(lockName, k -> new ReentrantLock());
        if (!lock.tryLock()) return null;
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    public boolean tryRunVoid(String lockName, Runnable body) {
        return tryRun(lockName, () -> { body.run(); return Boolean.TRUE; }) != null;
    }

    private boolean tryAcquire(String lockName) {
        Object ok = em.createNativeQuery(
                "select case when pg_try_advisory_lock(hashtext(:name)) then 1 else 0 end")
            .setParameter("name", lockName)
            .getSingleResult();
        return ok instanceof Number n && n.intValue() == 1;
    }

    private void release(String lockName) {
        try {
            em.createNativeQuery("select pg_advisory_unlock(hashtext(:name))")
                .setParameter("name", lockName)
                .getSingleResult();
        } catch (Exception ignored) {
            // 连接关闭时锁自动释放
        }
    }
}
