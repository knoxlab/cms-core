package com.powercms.cms.stats;

import com.powercms.cms.stats.entity.AnalyticsPlaySessionEntity;
import com.powercms.cms.stats.repo.AnalyticsWatchingRepository;
import com.powercms.cms.stats.web.AnalyticsQueryController;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时观看列表的播放状态与累计量闭环（FR-STAT-069 增强）：
 * 状态由最新视频事件 + 会话收口推导（播放中/已暂停/已停止），流量与观看时长取
 * analytics_play_session 会话累计（LEFT JOIN，文本侧比较容忍客户端伪造的脏 play_session_id）。
 *
 * <p>回归背景：观看列表原先只取 VIDEO_HEARTBEAT 且只带 play_position_ms——刚结束的访客
 * 会被错误标成播放中，暂停无从区分，运营看不到流量。另修一处显示缺陷：ip_masked 对回环
 * 地址末段置零后显示成 127.0.0.0（ClientFingerprint 同批修复，本测试经 collect 口径间接覆盖）。</p>
 */
@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "powercms.analytics.node-registration-enabled=false"
})
class AnalyticsWatchingRealtimeIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AnalyticsPlaybackService playbackService;

    @Autowired
    private AnalyticsWatchingRepository watchingRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    private static final Duration WINDOW = Duration.ofSeconds(300);

    @Test
    void watchingDerivesPlayStateAndSessionTotals() {
        UUID siteId = UUID.randomUUID();
        Instant now = Instant.now();
        // 会话 id 用标准 UUID（与生产一致；varchar(36) 转型按 36 字符口径比较）
        String playingSession = UUID.randomUUID().toString();
        String pausedSession = UUID.randomUUID().toString();
        String stoppedSession = UUID.randomUUID().toString();

        // 播放服务按生产口径逐请求独立事务调用（同事务里 upsert 的过期实体会在 END 收口时
        // 把原子累加的心跳累计值盖回 0——生产中 PLAY/HEARTBEAT/END 是三个独立请求，不会同框）；
        // 事件明细走一个已提交事务统一插入。
        playbackService.upsertPlaySession(playingSession, siteId, UUID.randomUUID(), null, null,
            "v-playing", null, now.minusSeconds(60), 600_000L, "/video/x", "desktop");
        playbackService.applyHeartbeat(playingSession, 20_000, 30_000L, 4096L);
        playbackService.upsertPlaySession(pausedSession, siteId, UUID.randomUUID(), null, null,
            "v-paused", null, now.minusSeconds(90), 600_000L, "/video/x", "desktop");
        playbackService.applyHeartbeat(pausedSession, 10_000, 15_000L, 1024L);
        playbackService.upsertPlaySession(stoppedSession, siteId, UUID.randomUUID(), null, null,
            "v-stopped", null, now.minusSeconds(120), 600_000L, "/video/x", "desktop");
        playbackService.applyHeartbeat(stoppedSession, 30_000, 45_000L, 8192L);
        playbackService.endPlaySession(stoppedSession, AnalyticsPlaySessionEntity.REASON_COMPLETED, 45_000L, 30_000L);

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // v-playing：PLAY + 心跳（未暂停、未收口）→ PLAYING，会话累计来自心跳累加
            insertVideoEvent(siteId, "VIDEO_PLAY", now.minusSeconds(60), "v-playing", playingSession, false, 0L, 0L);
            insertVideoEvent(siteId, "VIDEO_HEARTBEAT", now.minusSeconds(5), "v-playing", playingSession, false, 30_000L, 2048L);

            // v-paused：最新心跳带暂停位 → PAUSED
            insertVideoEvent(siteId, "VIDEO_HEARTBEAT", now.minusSeconds(3), "v-paused", pausedSession, true, 15_000L, 1024L);

            // v-stopped：心跳后 END，会话已收口（COMPLETED）→ STOPPED + 结束原因
            insertVideoEvent(siteId, "VIDEO_HEARTBEAT", now.minusSeconds(12), "v-stopped", stoppedSession, false, 45_000L, 4096L);
            insertVideoEvent(siteId, "VIDEO_END", now.minusSeconds(10), "v-stopped", stoppedSession, false, 45_000L, 4096L);

            // v-garbage：客户端伪造的非 uuid play_session_id——列表不得因此 500，累计量显示 0
            insertVideoEvent(siteId, "VIDEO_HEARTBEAT", now.minusSeconds(1), "v-garbage", "forged-not-uuid", false, 1_000L, 512L);
        });

        AnalyticsWatchingRepository.WatchingPage page = watchingRepo.watching(
            siteId, now.minus(WINDOW), null, "title", null, 0, 20);

        assertThat(page.total()).isEqualTo(4);
        List<AnalyticsWatchingRepository.WatchingRow> rows = page.rows();
        assertThat(rows).extracting(AnalyticsWatchingRepository.WatchingRow::visitorId)
            .containsExactlyInAnyOrder("v-playing", "v-paused", "v-stopped", "v-garbage");

        var playing = row(rows, "v-playing");
        assertThat(playing.eventType()).isEqualTo("VIDEO_HEARTBEAT");
        assertThat(playing.paused()).isFalse();
        assertThat(playing.endReason()).isNull();
        // 会话累计 = 心跳累加值（20_000ms / 4096B），不是最新一条事件的增量
        assertThat(playing.sessionWatchedMs()).isEqualTo(20_000);
        assertThat(playing.sessionTrafficBytes()).isEqualTo(4096);

        var paused = row(rows, "v-paused");
        assertThat(paused.paused()).isTrue();
        assertThat(paused.endReason()).isNull();
        assertThat(paused.sessionTrafficBytes()).isEqualTo(1024);

        var stopped = row(rows, "v-stopped");
        assertThat(stopped.eventType()).isEqualTo("VIDEO_END");
        assertThat(stopped.endReason()).isEqualTo(AnalyticsPlaySessionEntity.REASON_COMPLETED);
        assertThat(stopped.sessionWatchedMs()).isEqualTo(30_000);
        assertThat(stopped.sessionTrafficBytes()).isEqualTo(8192);

        var garbage = row(rows, "v-garbage");
        assertThat(garbage.sessionTrafficBytes()).isZero();
        assertThat(garbage.sessionWatchedMs()).isZero();
        assertThat(garbage.endReason()).isNull();
    }

    /** 播放状态推导口径（controller 层）：收口 > 暂停位 > 播放中。 */
    @Test
    void playStatePrefersClosedSessionThenPausedFlag() {
        assertThat(AnalyticsQueryController.playStateOf(false, null)).isEqualTo("PLAYING");
        assertThat(AnalyticsQueryController.playStateOf(true, null)).isEqualTo("PAUSED");
        assertThat(AnalyticsQueryController.playStateOf(false, "completed")).isEqualTo("STOPPED");
        // 已收口的会话即使最新事件仍带暂停位，也以「已停止」为准
        assertThat(AnalyticsQueryController.playStateOf(true, "timeout")).isEqualTo("STOPPED");
    }

    private static AnalyticsWatchingRepository.WatchingRow row(
        List<AnalyticsWatchingRepository.WatchingRow> rows, String visitorId) {
        return rows.stream().filter(r -> visitorId.equals(r.visitorId())).findFirst().orElseThrow();
    }

    /** 视频事件直插入库（独立已提交事务内调用）。 */
    private void insertVideoEvent(UUID siteId, String type, Instant occurredAt, String visitor,
                                  String playSessionId, boolean paused, long positionMs, long trafficBytes) {
        em.createNativeQuery("""
                insert into analytics_event (id, site_id, event_type, occurred_at, session_id, visitor_id,
                    route_path, title, play_session_id, play_position_ms, play_buffer, traffic_bytes, is_bot, is_spa_nav)
                values (:id, :siteId, :type, :at, :session, :visitor,
                    '/video/x', '演示视频', :playSessionId, :position, :paused, :traffic, :isBot, :isSpaNav)
                """)
            .setParameter("id", UUID.randomUUID())
            .setParameter("siteId", siteId)
            .setParameter("type", type)
            .setParameter("at", Timestamp.from(occurredAt))
            .setParameter("session", "s-" + visitor)
            .setParameter("visitor", visitor)
            .setParameter("playSessionId", playSessionId)
            .setParameter("position", positionMs)
            // bit/boolean 列以参数绑定（达梦无 false 字面量：Data type mismatch）
            .setParameter("paused", paused)
            .setParameter("traffic", trafficBytes)
            .setParameter("isBot", false)
            .setParameter("isSpaNav", false)
            .executeUpdate();
    }
}
