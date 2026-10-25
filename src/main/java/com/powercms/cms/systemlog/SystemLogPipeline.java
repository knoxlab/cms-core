package com.powercms.cms.systemlog;

import com.powercms.cms.systemlog.entity.AccessLogEntity;
import com.powercms.cms.systemlog.entity.RuntimeLogEntity;
import com.powercms.cms.systemlog.repo.AccessLogRepository;
import com.powercms.cms.systemlog.repo.RuntimeLogRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 日志写入管道（FR-LOG-ST-002 / NFR-LOG-004 / NFR-LOG-011）：
 * <ul>
 *   <li><b>写入不阻塞</b>：采集端（Logback Appender / AccessLogFilter）只做入队，
 *       队列满时丢弃 TRACE/DEBUG 并累计丢弃摘要（数量 + 时间段），绝不阻塞业务线程；</li>
 *   <li><b>批量落库</b>：后台单写者线程每 {@code flushInterval}（或凑满 batchSize）批量
 *       {@code saveAll}，绕开逐条事务；</li>
 *   <li><b>DB 瞬断自愈</b>：写库失败时条目回队重试，超过队列容量才丢弃低级别；
 *       恢复后自动续写，不影响业务进程（NFR-LOG-011 / AC-LOG-027）。</li>
 * </ul>
 *
 * <p>文件 sink 由 Logback 自身的 RollingFileAppender 承载，与本管道物理独立：
 * DB 失败不影响文件通道（NFR-LOG-013 双 sink 故障隔离）。</p>
 */
@Component
public class SystemLogPipeline {

    static final class Entry {
        final RuntimeLogEntity runtime;
        final AccessLogEntity access;
        /** 写库失败重试次数（FR-LOG-ST-002：失败回队重试，超过上限放弃低级别）。 */
        int attempts;
        Entry(RuntimeLogEntity runtime) { this.runtime = runtime; this.access = null; }
        Entry(AccessLogEntity access) { this.runtime = null; this.access = access; }
    }

    private static final Logger log = LoggerFactory.getLogger(SystemLogPipeline.class);
    /** 同一批次最大重试轮数（防 DB 瞬断时无限回队自旋；超出即丢弃并计摘要）。 */
    private static final int MAX_FLUSH_ATTEMPTS = 3;

    private final SystemLogProperties props;
    private final RuntimeLogRepository runtimeRepo;
    private final AccessLogRepository accessRepo;
    private final BlockingQueue<Entry> queue;
    private final Thread writer;
    private final ReentrantLock flushLock = new ReentrantLock();
    private volatile boolean running = true;

    /** 观测指标：入队 / 落库 / 丢弃计数（NFR-LOG-004 丢弃行为可控有计数）。 */
    final AtomicLong enqueued = new AtomicLong();
    final AtomicLong persistedRuntime = new AtomicLong();
    final AtomicLong persistedAccess = new AtomicLong();
    final AtomicLong dropped = new AtomicLong();
    volatile Instant firstDropAt;
    volatile Instant lastDropAt;

    @org.springframework.beans.factory.annotation.Autowired
    public SystemLogPipeline(SystemLogProperties props,
                             RuntimeLogRepository runtimeRepo,
                             AccessLogRepository accessRepo) {
        this(props, runtimeRepo, accessRepo, true);
    }

    /** startWriter=false 供测试同步验证溢出行为（无写者线程竞争）。 */
    SystemLogPipeline(SystemLogProperties props,
                      RuntimeLogRepository runtimeRepo,
                      AccessLogRepository accessRepo,
                      boolean startWriter) {
        this.props = props;
        this.runtimeRepo = runtimeRepo;
        this.accessRepo = accessRepo;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, props.getQueueCapacity()));
        this.writer = new Thread(this::drainLoop, "system-log-pipeline");
        this.writer.setDaemon(true);
        if (startWriter) this.writer.start();
    }

    /** 运行日志入队（采集侧调用；脱敏在入队前完成，队列内无敏感明文）。 */
    public boolean submit(RuntimeLogEntity entity) {
        return submit(new Entry(entity));
    }

    /** 访问日志入队。 */
    public boolean submit(AccessLogEntity entity) {
        return submit(new Entry(entity));
    }

    private boolean submit(Entry entry) {
        if (queue.offer(entry)) {
            enqueued.incrementAndGet();
            return true;
        }
        return onOverflow(entry);
    }

    /**
     * 队列满：丢弃 TRACE/DEBUG（运行日志）并记录丢弃摘要；高级别运行日志与访问日志
     * 覆盖式腾位后入队（不阻塞业务线程为第一优先）。
     *
     * @return incoming 是否被接受（入队成功 = true；被策略丢弃或腾位失败 = false）
     */
    private boolean onOverflow(Entry incoming) {
        if (incoming.runtime != null) {
            String level = incoming.runtime.getLevel();
            if (RuntimeLogEntity.LEVEL_TRACE.equals(level) || RuntimeLogEntity.LEVEL_DEBUG.equals(level)) {
                drop(incoming);
                return false;
            }
            // 高级别条目：先腾掉一条再入队
            Entry evicted = queue.poll();
            if (evicted != null) drop(evicted);
            if (queue.offer(incoming)) {
                enqueued.incrementAndGet();
                return true;
            }
            drop(incoming);
            return false;
        }
        // 访问日志：一律保留最新（覆盖式腾位）
        Entry evicted = queue.poll();
        if (evicted != null) drop(evicted);
        if (queue.offer(incoming)) {
            enqueued.incrementAndGet();
            return true;
        }
        drop(incoming);
        return false;
    }

    private void drop(Entry entry) {
        dropped.incrementAndGet();
        Instant now = Instant.now();
        if (firstDropAt == null) firstDropAt = now;
        lastDropAt = now;
    }

    private void drainLoop() {
        Duration interval = props.getFlushInterval();
        while (running || !queue.isEmpty()) {
            try {
                List<Entry> batch = drainBatch(props.getBatchSize());
                if (!batch.isEmpty()) flush(batch);
                else Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("system log pipeline flush error: {}", e.getMessage());
            }
        }
    }

    private List<Entry> drainBatch(int max) throws InterruptedException {
        List<Entry> batch = new ArrayList<>(max);
        Entry first = queue.poll(props.getFlushInterval().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (first != null) batch.add(first);
        queue.drainTo(batch, max - batch.size());
        return batch;
    }

    /**
     * 批量落库；失败时整批回队（有界，超出即丢弃并计摘要）。
     * 关键约束：两类条目分别写各自仓库，任一失败不影响另一类（sink 内部再隔离）。
     */
    void flush(List<Entry> batch) {
        flushLock.lock();
        try {
            List<Entry> runtimes = new ArrayList<>();
            List<Entry> accesses = new ArrayList<>();
            for (Entry e : batch) {
                if (e.runtime != null) runtimes.add(e);
                else accesses.add(e);
            }
            persistRuntimes(runtimes);
            persistAccesses(accesses);
        } finally {
            flushLock.unlock();
        }
    }

    private void persistRuntimes(List<Entry> runtimes) {
        if (runtimes.isEmpty()) return;
        List<RuntimeLogEntity> entities = runtimes.stream().map(e -> e.runtime).toList();
        try {
            runtimeRepo.saveAll(entities);
            runtimeRepo.flush();
            persistedRuntime.addAndGet(entities.size());
        } catch (RuntimeException e) {
            requeueOrDrop(runtimes, e);
        }
    }

    private void persistAccesses(List<Entry> accesses) {
        if (accesses.isEmpty()) return;
        List<AccessLogEntity> entities = accesses.stream().map(e -> e.access).toList();
        try {
            accessRepo.saveAll(entities);
            accessRepo.flush();
            persistedAccess.addAndGet(entities.size());
        } catch (RuntimeException e) {
            requeueOrDrop(accesses, e);
        }
    }

    /**
     * 写库失败：整批回队重试（NFR-LOG-011 自愈）；同一批累计重试超过 {@link #MAX_FLUSH_ATTEMPTS}
     * 次仍失败则放弃，避免 DB 瞬断恢复前无限自旋（放弃即计丢弃摘要）。
     */
    private void requeueOrDrop(List<Entry> entries, Exception cause) {
        int offered = 0;
        int abandoned = 0;
        for (Entry e : entries) {
            e.attempts++;
            if (e.attempts > MAX_FLUSH_ATTEMPTS) {
                drop(e);
                abandoned++;
                continue;
            }
            if (queue.offer(e)) offered++;
            else { drop(e); abandoned++; }
        }
        if (offered == 0 && abandoned > 0) {
            log.error("system log pipeline dropped {} entries after {} failed attempts (DB unavailable)",
                abandoned, MAX_FLUSH_ATTEMPTS, cause);
        } else if (offered > 0) {
            log.warn("system log pipeline retrying {} entries (abandoned {}): {}", offered, abandoned, cause.getMessage());
        }
    }

    /** 队列内丢弃摘要（FR-LOG-ST-002：数量与时间段），供监控指标读取。 */
    public String dropSummary() {
        if (dropped.get() == 0) return null;
        return "dropped=" + dropped.get()
            + " first=" + (firstDropAt == null ? "-" : firstDropAt)
            + " last=" + (lastDropAt == null ? "-" : lastDropAt);
    }

    /** 管道观测指标快照（NFR-LOG-004；供 Web 层读取，避免直接暴露内部字段）。 */
    public java.util.Map<String, Object> metrics() {
        return java.util.Map.of(
            "queueSize", queueSize(),
            "enqueued", enqueued.get(),
            "persistedRuntime", persistedRuntime.get(),
            "persistedAccess", persistedAccess.get(),
            "dropped", dropped.get(),
            "dropSummary", dropSummary() == null ? "" : dropSummary());
    }

    @PreDestroy
    void shutdown() {
        running = false;
        writer.interrupt(); // 唤醒 poll/sleep，使长 flushInterval 下也能及时退出
        try {
            writer.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    int queueSize() { return queue.size(); }
}
