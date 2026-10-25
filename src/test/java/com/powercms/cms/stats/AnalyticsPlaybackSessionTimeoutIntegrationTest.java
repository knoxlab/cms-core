package com.powercms.cms.stats;

import com.powercms.cms.stats.entity.AnalyticsPlaySessionEntity;
import com.powercms.cms.stats.repo.AnalyticsPlaySessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 播放会话超时收口判活（NFR-STAT-006）闭环：
 * 收口只认「最后一次心跳是否早于阈值」，不按起播时间一刀切。
 *
 * <p>回归背景：旧条件 {@code started_at < now - timeout} 会把长视频里仍在正常心跳的会话
 * 提前标记 TIMEOUT，VIDEO_END 到达时 {@code ended_at} 已被占用，真实结束原因写不进去
 * （完播率/结束原因失真）。实测 100 秒视频：19:26:40 起播、心跳持续到 19:36，
 * 会话却在 19:28:02 被 TIMEOUT 收口。</p>
 */
@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "powercms.analytics.node-registration-enabled=false",
    "powercms.analytics.session-timeout-ms=60000"
})
class AnalyticsPlaybackSessionTimeoutIntegrationTest {

    @Autowired
    private AnalyticsPlaybackService playbackService;

    @Autowired
    private AnalyticsPlaySessionRepository sessions;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    /** 播放中的会话（起播 5 分钟，但心跳一直在到）不被收口，END 仍能写入真实结束原因。 */
    @Test
    void liveSessionSurvivesTimeoutCloseAndKeepsRealEndReason() {
        Instant now = Instant.now();
        String id = "ps-live-" + UUID.randomUUID();
        createSession(id, now.minus(Duration.ofMinutes(5)));
        // 心跳刚到：last_heartbeat_at = now
        playbackService.applyHeartbeat(id, 10_000, 10_000L, 1024L);

        playbackService.closeTimedOutSessions(now);

        AnalyticsPlaySessionEntity live = read(id);
        assertThat(live.getEndedAt()).isNull();
        assertThat(live.getEndReason()).isNull();
        assertThat(live.getWatchedMs()).isEqualTo(10_000);

        playbackService.endPlaySession(id, AnalyticsPlaySessionEntity.REASON_COMPLETED, 600_000L, 60_000L);
        AnalyticsPlaySessionEntity closed = read(id);
        assertThat(closed.getEndedAt()).isNotNull();
        assertThat(closed.getEndReason()).isEqualTo(AnalyticsPlaySessionEntity.REASON_COMPLETED);
    }

    /** 心跳停摆超过阈值（起播久远且无新心跳）的会话照常 TIMEOUT 收口。 */
    @Test
    void idleSessionClosesWithTimeout() {
        Instant now = Instant.now();
        String id = "ps-idle-" + UUID.randomUUID();
        createSession(id, now.minus(Duration.ofMinutes(5)));

        playbackService.closeTimedOutSessions(now);

        AnalyticsPlaySessionEntity session = read(id);
        assertThat(session.getEndedAt()).isNotNull();
        assertThat(session.getEndReason()).isEqualTo(AnalyticsPlaySessionEntity.REASON_TIMEOUT);
    }

    /** 存量行（last_heartbeat_at 为空）退化为按起播时间收口，保持迁移前语义。 */
    @Test
    void legacyRowWithoutHeartbeatFallsBackToStartedAt() {
        Instant now = Instant.now();
        String id = "ps-legacy-" + UUID.randomUUID();
        createSession(id, now.minus(Duration.ofMinutes(5)));
        new TransactionTemplate(txManager).executeWithoutResult(status ->
            em.createNativeQuery("update analytics_play_session set last_heartbeat_at = null where id = :id")
                .setParameter("id", id)
                .executeUpdate());

        playbackService.closeTimedOutSessions(now);

        assertThat(read(id).getEndReason()).isEqualTo(AnalyticsPlaySessionEntity.REASON_TIMEOUT);
    }

    /** 心跳累加同时刷新 last_heartbeat_at（收口判活的数据来源）。 */
    @Test
    void heartbeatRefreshesLastHeartbeatAt() {
        Instant now = Instant.now();
        String id = "ps-hb-" + UUID.randomUUID();
        createSession(id, now.minus(Duration.ofMinutes(5)));
        // H2 时间列精度为微秒，与 Instant 纳秒有出入，按秒级容差断言
        assertThat(read(id).getLastHeartbeatAt())
            .isCloseTo(now.minus(Duration.ofMinutes(5)), within(Duration.ofSeconds(1)));

        playbackService.applyHeartbeat(id, 5_000, 5_000L, 512L);

        AnalyticsPlaySessionEntity after = read(id);
        assertThat(after.getLastHeartbeatAt()).isAfter(now.minus(Duration.ofSeconds(5)));
        assertThat(after.getWatchedMs()).isEqualTo(5_000);
    }

    private void createSession(String id, Instant startedAt) {
        playbackService.upsertPlaySession(id, UUID.randomUUID(), UUID.randomUUID(), null, null,
            "visitor-" + id, null, startedAt, 600_000L, "/video/x", "desktop");
    }

    /** 独立事务读取（JPQL 更新不清一级缓存，读前 clear 保证断言取自数据库）。 */
    private AnalyticsPlaySessionEntity read(String id) {
        return new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            return sessions.findById(id).orElseThrow();
        });
    }
}
