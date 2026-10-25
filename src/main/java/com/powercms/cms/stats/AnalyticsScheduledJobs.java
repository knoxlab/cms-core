package com.powercms.cms.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 统计定时任务集（FR-STAT-068/105/107）：
 * - 节点心跳注册与超时标记；
 * - 分钟在线快照：leader 写全局 __ALL__ 行（跨节点去重），各节点行按节点过滤；
 * - 心跳超时播放会话收口（NFR-STAT-006）；
 * - 日内增量聚合：当日各维度表高频重算，报表 T+0 可见（凌晨日任务保持 T+1 最终口径）。
 * 全部任务经 advisory lock 防重（FR-STAT-105），多实例同时调度仅一个节点执行。
 */
@Component
public class AnalyticsScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsScheduledJobs.class);

    private final AnalyticsNodeRegistry registry;
    private final AnalyticsJobLockService locks;
    private final AnalyticsEventStore eventStore;
    private final AnalyticsAggregateService aggregateService;
    private final AnalyticsPlaybackService playbackService;
    private final boolean nodeRegistrationEnabled;

    public AnalyticsScheduledJobs(AnalyticsNodeRegistry registry,
                                  AnalyticsJobLockService locks,
                                  AnalyticsEventStore eventStore,
                                  AnalyticsAggregateService aggregateService,
                                  AnalyticsPlaybackService playbackService,
                                  @Value("${powercms.analytics.node-registration-enabled:true}") boolean nodeRegistrationEnabled) {
        this.registry = registry;
        this.locks = locks;
        this.eventStore = eventStore;
        this.aggregateService = aggregateService;
        this.playbackService = playbackService;
        this.nodeRegistrationEnabled = nodeRegistrationEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (nodeRegistrationEnabled) {
            try {
                registry.register();
            } catch (IllegalStateException e) {
                log.error("analytics node registration rejected: {}", e.getMessage());
                throw e;
            }
        }
    }

    @Scheduled(fixedDelayString = "${powercms.analytics.heartbeat-ms:30000}")
    public void heartbeat() {
        if (!nodeRegistrationEnabled) return;
        try {
            registry.heartbeat();
        } catch (Exception e) {
            log.warn("analytics heartbeat failed: {}", e.getMessage());
        }
    }

    /** 每分钟在线快照（FR-STAT-068）：leader 单点写 __ALL__ 全局行 + 本节点行。 */
    @Scheduled(cron = "${powercms.analytics.snapshot-cron:0 * * * * *}")
    public void minuteSnapshot() {
        locks.tryRunVoid("analytics:minute-snapshot", () -> {
            Instant minute = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            aggregateService.writeGlobalMinuteSnapshot(minute);
            aggregateService.writeNodeMinuteSnapshots(minute);
        });
    }

    /** 心跳超时会话收口（NFR-STAT-006）：每 60 秒。 */
    @Scheduled(fixedDelayString = "${powercms.analytics.session-close-ms:60000}")
    public void closeTimedOutPlaySessions() {
        locks.tryRunVoid("analytics:close-play-sessions", () ->
            playbackService.closeTimedOutSessions(Instant.now()));
    }

    /** 日聚合（含去重基表，FR-STAT-017）：T+1 凌晨站点时区。UTC 02:00 ≈ 北京 10:00 前一天，可按部署时区调整 cron。 */
    @Scheduled(cron = "${powercms.analytics.daily-aggregate-cron:0 0 2 * * *}")
    public void dailyAggregate() {
        locks.tryRunVoid("analytics:daily-aggregate", () ->
            aggregateService.aggregateYesterday(Instant.now()));
    }

    /**
     * 日内增量聚合（当日，站点时区）：复用幂等 delete+insert 重算当日各维度表，
     * 让客户端/来源/忠诚度等维度报表与概览/趋势 T+0（分钟级延迟）可见；
     * 不替代凌晨日任务的 T+1 最终口径（跨日去重基表收口仍由其负责）。
     */
    @Scheduled(cron = "${powercms.analytics.intraday-aggregate-cron:0 */10 * * * *}")
    public void intradayAggregate() {
        locks.tryRunVoid("analytics:intraday-aggregate", () ->
            aggregateService.aggregateToday(Instant.now()));
    }
}
