package com.powercms.cms.stats;

import com.powercms.cms.stats.entity.AnalyticsPlaySessionEntity;
import com.powercms.cms.stats.repo.AnalyticsPlaySessionRepository;
import com.powercms.cms.stats.repo.MemberProgressRepository;
import com.powercms.cms.stats.repo.MemberProgressStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-STAT-034/037 验收：会话幂等、心跳原子累加、学习进度合并、超时收口。
 */
class AnalyticsPlaybackServiceTest {

    private final AnalyticsPlaySessionRepository sessions = mock(AnalyticsPlaySessionRepository.class);
    private final MemberProgressRepository progress = mock(MemberProgressRepository.class);
    private final MemberProgressStore progressStore = mock(MemberProgressStore.class);
    private final AnalyticsPlaybackService service =
        new AnalyticsPlaybackService(sessions, progress, progressStore, 60_000, 95);

    @Test
    void playEventCreatesSessionOnce() {
        Instant startedAt = Instant.now();
        when(sessions.findById("ps-1")).thenReturn(Optional.empty());
        boolean created = service.upsertPlaySession("ps-1", UUID.randomUUID(), UUID.randomUUID(),
            null, null, "v-1", null, startedAt, 300_000L, "/video/a", "desktop");
        assertThat(created).isTrue();

        ArgumentCaptor<AnalyticsPlaySessionEntity> captor = ArgumentCaptor.forClass(AnalyticsPlaySessionEntity.class);
        verify(sessions).save(captor.capture());
        assertThat(captor.getValue().getDurationMs()).isEqualTo(300_000L);
        // 心跳活跃时间从起播开始计：收口判活只看「多久没心跳」
        assertThat(captor.getValue().getLastHeartbeatAt()).isEqualTo(startedAt);

        // 重复 PLAY：不重建
        when(sessions.findById("ps-1")).thenReturn(Optional.of(captor.getValue()));
        assertThat(service.upsertPlaySession("ps-1", UUID.randomUUID(), UUID.randomUUID(),
            null, null, "v-1", null, Instant.now(), 300_000L, "/video/a", "desktop")).isFalse();
    }

    @Test
    void heartbeatAppliesAtomicDelta() {
        service.applyHeartbeat("ps-1", 10_000, 45_000L, 1024L);
        verify(sessions).applyHeartbeat(eq("ps-1"), eq(10_000L), eq(45_000L), eq(1024L), any(Instant.class));
        // 负增量钳制为 0
        service.applyHeartbeat("ps-1", -5, -10L, -1L);
        verify(sessions).applyHeartbeat(eq("ps-1"), eq(0L), eq(0L), eq(0L), any(Instant.class));
    }

    @Test
    void endSessionMergesMemberProgress() {
        UUID siteId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        AnalyticsPlaySessionEntity session = new AnalyticsPlaySessionEntity();
        session.setId("ps-1");
        session.setSiteId(siteId);
        session.setMemberId(memberId);
        session.setContentId(contentId);
        session.setDurationMs(100_000L);
        session.setWatchedMs(30_000);
        session.setFarthestMs(40_000);
        when(sessions.findById("ps-1")).thenReturn(Optional.of(session));

        service.endPlaySession("ps-1", AnalyticsPlaySessionEntity.REASON_EXIT, 60_000L, null);

        // 会话收口
        assertThat(session.getEndedAt()).isNotNull();
        assertThat(session.getEndReason()).isEqualTo(AnalyticsPlaySessionEntity.REASON_EXIT);
        assertThat(session.getFarthestMs()).isEqualTo(60_000L);
        // 学习进度：净观看 30s 累计、最远位置 60s
        verify(progressStore).upsert(any(), eq(siteId), eq(memberId), eq(contentId),
            eq(30_000L), eq(60_000L), eq(60_000L), any());
        // 完播判定下推 SQL 原子条件（farthest_ms >= 95s 时置位），服务以计算阈值调用
        verify(progress).markCompletedIfReached(eq(siteId), eq(memberId), eq(contentId), eq(95_000L), any(), eq(true));
    }

    @Test
    void endSessionMarksCompletedAtThreshold() {
        UUID siteId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        AnalyticsPlaySessionEntity session = new AnalyticsPlaySessionEntity();
        session.setId("ps-2");
        session.setSiteId(siteId);
        session.setMemberId(memberId);
        session.setContentId(contentId);
        session.setDurationMs(100_000L);
        session.setWatchedMs(96_000);
        session.setFarthestMs(96_000);
        when(sessions.findById("ps-2")).thenReturn(Optional.of(session));

        service.endPlaySession("ps-2", AnalyticsPlaySessionEntity.REASON_COMPLETED, 96_000L, 96_000L);
        verify(progress).markCompletedIfReached(eq(siteId), eq(memberId), eq(contentId), eq(95_000L), any(), eq(true));
    }

    @Test
    void closeTimedOutDelegates() {
        when(sessions.closeTimedOut(any(), any(), anyString())).thenReturn(3);
        assertThat(service.closeTimedOutSessions(Instant.now())).isEqualTo(3);
    }
}
