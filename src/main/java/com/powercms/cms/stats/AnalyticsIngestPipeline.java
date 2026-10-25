package com.powercms.cms.stats;

import com.powercms.cms.stats.entity.AnalyticsEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 批量落库管道（FR-STAT-102、NFR-STAT-002）：
 * 采集线程仅入有界队列，落库线程批量幂等写，队列满丢弃最旧并计数，
 * 绝不阻塞采集请求与业务 API。多节点时各节点独立运行本管道，event_id 幂等保证全局一致。
 */
@Service
public class AnalyticsIngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsIngestPipeline.class);

    private final AnalyticsEventStore eventStore;
    private final TransactionTemplate txTemplate;
    private final Consumer<AnalyticsEventEntity> sideEffects;
    private final int batchSize;
    private final int flushIntervalMs;
    private final ExecutorService worker;
    private final java.util.ArrayDeque<AnalyticsEventEntity> queue = new java.util.ArrayDeque<>();
    private final int capacity;
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong acceptedCount = new AtomicLong();
    private volatile boolean draining;

    public AnalyticsIngestPipeline(AnalyticsEventStore eventStore,
                                   TransactionTemplate txTemplate,
                                   AnalyticsPlaybackService playbackService,
                                   @Value("${powercms.analytics.batch-size:200}") int batchSize,
                                   @Value("${powercms.analytics.flush-interval-ms:1000}") int flushIntervalMs,
                                   @Value("${powercms.analytics.queue-capacity:10000}") int queueCapacity) {
        this.eventStore = eventStore;
        this.txTemplate = txTemplate;
        // 播放副作用仅在落库确认新插入后触发（FR-STAT-102 精确幂等）：重放事件不会重复累加心跳
        this.sideEffects = playbackService::onEventCollected;
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMs = Math.max(50, flushIntervalMs);
        this.capacity = Math.max(this.batchSize, queueCapacity);
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "analytics-ingest");
            t.setDaemon(true);
            return t;
        });
        this.worker.submit(this::loop);
    }

    /** 采集入口：队列满时丢弃最旧并计数，快速返回。 */
    public void offer(AnalyticsEventEntity event) {
        if (draining) {
            droppedCount.incrementAndGet();
            return;
        }
        boolean overflow;
        synchronized (queue) {
            while (queue.size() >= capacity) {
                queue.pollFirst();
                droppedCount.incrementAndGet();
            }
            queue.addLast(event);
            overflow = queue.size() >= capacity;
            queueDepth.set(queue.size());
        }
        acceptedCount.incrementAndGet();
    }

    private void loop() {
        List<AnalyticsEventEntity> batch = new ArrayList<>(batchSize);
        while (true) {
            try {
                batch.clear();
                synchronized (queue) {
                    while (batch.size() < batchSize && !queue.isEmpty()) {
                        batch.add(queue.pollFirst());
                    }
                    queueDepth.set(queue.size());
                }
                if (batch.isEmpty()) {
                    Thread.sleep(flushIntervalMs);
                    continue;
                }
                persist(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("analytics batch persist failed size={} droppedSoFar={}", batch.size(), droppedCount.get(), e);
            }
        }
    }

    /** 独立事务批量幂等写入；失败不重排（保序与吞吐优先，计数告警由指标承接）。
     *  仅对实际新插入的事件执行派生副作用（播放会话/学习进度），重放零副作用（FR-STAT-102）。 */
    private void persist(List<AnalyticsEventEntity> batch) {
        List<AnalyticsEventEntity> fresh = txTemplate.execute(status -> eventStore.insertIgnoreBatch(batch));
        if (fresh != null) {
            for (AnalyticsEventEntity e : fresh) {
                try {
                    sideEffects.accept(e);
                } catch (Exception ex) {
                    log.warn("analytics side effect failed eventId={} type={}", e.getId(), e.getEventType(), ex);
                }
            }
        }
    }

    /** 优雅下线（FR-STAT-107）：停止接收新事件并等待在途队列 flush。 */
    public void drain(long timeoutMs) {
        draining = true;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (queueDepth.get() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    public int queueDepth() { return queueDepth.get(); }
    public long droppedCount() { return droppedCount.get(); }
    public long acceptedCount() { return acceptedCount.get(); }
    public boolean draining() { return draining; }
}
