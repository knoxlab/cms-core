package com.powercms.cms.stats;

import com.powercms.cms.stats.entity.AnalyticsPlaySessionEntity;
import com.powercms.cms.stats.repo.AnalyticsPlaySessionRepository;
import com.powercms.cms.stats.repo.MemberProgressRepository;
import com.powercms.cms.stats.repo.MemberProgressStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 播放会话与会员学习进度服务（FR-STAT-034/037、NFR-STAT-006）。
 * 全部写入为幂等 upsert + 数据库侧原子累加（FR-STAT-102），多节点并发重放结果一致。
 * 完播判定：最远位置达到视频时长 95%（可配置，FR-STAT-035 术语表）。
 */
@Service
public class AnalyticsPlaybackService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsPlaybackService.class);

    private final AnalyticsPlaySessionRepository playSessionRepo;
    private final MemberProgressRepository memberProgressRepo;
    private final MemberProgressStore memberProgressStore;
    private final long sessionTimeoutMs;
    private final long completionThresholdPercent;

    public AnalyticsPlaybackService(AnalyticsPlaySessionRepository playSessionRepo,
                                    MemberProgressRepository memberProgressRepo,
                                    MemberProgressStore memberProgressStore,
                                    @Value("${powercms.analytics.session-timeout-ms:60000}") long sessionTimeoutMs,
                                    @Value("${powercms.analytics.completion-percent:95}") long completionThresholdPercent) {
        this.playSessionRepo = playSessionRepo;
        this.memberProgressRepo = memberProgressRepo;
        this.memberProgressStore = memberProgressStore;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.completionThresholdPercent = Math.min(100, Math.max(1, completionThresholdPercent));
    }

    /**
     * PLAY/HEARTBEAT 事件会话落库。幂等：会话已存在直接返回 false（重复 PLAY 不重建）。
     * member_id 由服务端从会员会话解析注入（FR-STAT-037），客户端不可上报。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean upsertPlaySession(String playSessionId, UUID siteId, UUID contentId, UUID contentTypeId,
                                     UUID mediaAssetId, String visitorId, UUID memberId,
                                     Instant startedAt, Long videoDurationMs, String referrerPath, String deviceType) {
        if (playSessionRepo.findById(playSessionId).isPresent()) return false;
        AnalyticsPlaySessionEntity s = new AnalyticsPlaySessionEntity();
        s.setId(playSessionId);
        s.setSiteId(siteId);
        s.setContentId(contentId);
        s.setContentTypeId(contentTypeId);
        s.setMediaAssetId(mediaAssetId);
        s.setVisitorId(visitorId);
        s.setMemberId(memberId);
        s.setStartedAt(startedAt);
        // 心跳活跃时间从起播开始计：收口判定看"多久没心跳"（NFR-STAT-006），
        // 长视频只要还在发心跳就不算超时
        s.setLastHeartbeatAt(startedAt == null ? Instant.now() : startedAt);
        s.setDurationMs(videoDurationMs);
        s.setReferrerPath(referrerPath);
        s.setDeviceType(deviceType);
        playSessionRepo.save(s);
        return true;
    }

    /** 心跳：净观看增量与最远位置原子累加（FR-STAT-102），并刷新 last_heartbeat_at 供超时收口判活。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void applyHeartbeat(String playSessionId, long deltaMs, Long positionMs, Long trafficBytes) {
        int updated = playSessionRepo.applyHeartbeat(playSessionId,
            Math.max(0, deltaMs),
            positionMs == null ? 0 : Math.max(0, positionMs),
            trafficBytes == null ? 0 : Math.max(0, trafficBytes),
            Instant.now());
        if (updated == 0) {
            log.debug("heartbeat for unknown/expired session {}", playSessionId);
        }
    }

    /**
     * 播放结束（FR-STAT-034）：收口会话并记录结束原因；
     * 登录会员合并学习进度（FR-STAT-037：跨会话累计、最远位置取 max、断点续播）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void endPlaySession(String playSessionId, String endReason, Long farthestMs, Long watchedMs) {
        playSessionRepo.findById(playSessionId).ifPresent(session -> {
            Instant now = Instant.now();
            if (session.getEndedAt() == null) {
                session.setEndedAt(now);
                session.setEndReason(endReason);
                session.setLastHeartbeatAt(now);
                if (farthestMs != null && farthestMs > session.getFarthestMs()) session.setFarthestMs(farthestMs);
                playSessionRepo.save(session);
            }
            if (session.getMemberId() != null && session.getContentId() != null) {
                long deltaWatched = Math.max(0, watchedMs == null ? session.getWatchedMs() : watchedMs);
                long farthest = Math.max(session.getFarthestMs(), farthestMs == null ? 0 : farthestMs);
                memberProgressStore.upsert(UUID.randomUUID(), session.getSiteId(), session.getMemberId(),
                    session.getContentId(), deltaWatched, farthest, farthest, now);
                if (session.getDurationMs() != null && session.getDurationMs() > 0) {
                    memberProgressRepo.markCompletedIfReached(session.getSiteId(), session.getMemberId(),
                        session.getContentId(), session.getDurationMs() * completionThresholdPercent / 100, now, true);
                }
            }
        });
    }

    /** 心跳超时会话收口（NFR-STAT-006）：仅收口「最后一次心跳」早于阈值的未结束会话；
     *  播放中的会话只要心跳还在到就不收口，END 到达时仍能写入真实结束原因。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int closeTimedOutSessions(Instant now) {
        int closed = playSessionRepo.closeTimedOut(now.minusMillis(sessionTimeoutMs), now,
            AnalyticsPlaySessionEntity.REASON_TIMEOUT);
        if (closed > 0) log.debug("closed {} timed-out play sessions", closed);
        return closed;
    }

    /**
     * 采集事件接流入口（FR-STAT-034）：VIDEO_PLAY 建会话、VIDEO_HEARTBEAT 原子累加、
     * VIDEO_END 收口并合并学习进度。仅由落库管道对「新插入」事件调用（精确幂等）；
     * 会员身份已由采集端服务端解析并随事件落库（event.member_id），此处不再另行解析。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onEventCollected(com.powercms.cms.stats.entity.AnalyticsEventEntity event) {
        String type = event.getEventType();
        if (!EventTypes.isVideo(type)) return;
        String playSessionId = event.getPlaySessionId();
        if (playSessionId == null || playSessionId.isBlank()) {
            playSessionId = event.getId().toString();
            event.setPlaySessionId(playSessionId);
        }
        if (EventTypes.VIDEO_PLAY.equals(type)) {
            upsertPlaySession(playSessionId, event.getSiteId(), event.getContentId(), event.getContentTypeId(),
                event.getMediaAssetId(), event.getVisitorId(), event.getMemberId(), event.getOccurredAt(),
                event.getDurationMs(), event.getRoutePath(), event.getDeviceType());
        } else if (EventTypes.VIDEO_HEARTBEAT.equals(type)) {
            applyHeartbeat(playSessionId,
                event.getPlayDeltaMs() == null ? 0 : event.getPlayDeltaMs(),
                event.getPlayPositionMs(),
                event.getTrafficBytes());
        } else if (EventTypes.VIDEO_END.equals(type)) {
            endPlaySession(playSessionId, event.getEndReason(), event.getFarthestMs(), event.getPlayDeltaMs());
        }
    }
}
