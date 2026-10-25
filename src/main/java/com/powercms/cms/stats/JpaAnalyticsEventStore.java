package com.powercms.cms.stats;

import com.powercms.cms.stats.entity.AnalyticsEventEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 事件批量幂等写入与实时在线统计的原生实现。
 * 幂等写按方言分支（AnalyticsDialect）：PG 系（PostgreSQL/金仓）用原子 ON CONFLICT DO NOTHING；
 * H2 2.x / MySQL / 达梦不支持该子句，用「insert ... select ... where not exists」可移植写法
 * （FROM 片段按方言注入：values(1) / 无 FROM / from dual，见 notExistsFromClause）。
 * 在线快照：窗口内按 visitor 去重（FR-STAT-066），global 路径不带 server_node 过滤以跨节点去重（FR-STAT-106）。
 */
@Service
public class JpaAnalyticsEventStore implements AnalyticsEventStore {

    private final AnalyticsDialect dialect;

    public JpaAnalyticsEventStore(AnalyticsDialect dialect) {
        this.dialect = dialect;
    }

    @PersistenceContext
    private EntityManager em;

    private static final String INSERT_SQL = """
        insert into analytics_event (
            id, site_id, event_type, occurred_at, session_id, visitor_id,
            session_started_at, session_seq, member_id, ip_hash, ip_masked,
            region_province, region_city, server_node, route_path, title,
            content_id, content_type_id, media_asset_id, folder_id,
            referer_host, source_type, search_keyword, utm_source, utm_medium, utm_campaign,
            os, browser, browser_version, device_type, language, screen,
            duration_ms, is_bot, is_spa_nav, play_session_id, play_position_ms,
            play_delta_ms, play_buffer, traffic_bytes, end_reason, farthest_ms, error_status, attrs
        ) values (
            :id, :siteId, :eventType, :occurredAt, :sessionId, :visitorId,
            :sessionStartedAt, :sessionSeq, :memberId, :ipHash, :ipMasked,
            :regionProvince, :regionCity, :serverNode, :routePath, :title,
            :contentId, :contentTypeId, :mediaAssetId, :folderId,
            :refererHost, :sourceType, :searchKeyword, :utmSource, :utmMedium, :utmCampaign,
            :os, :browser, :browserVersion, :deviceType, :language, :screen,
            :durationMs, :isBot, :isSpaNav, :playSessionId, :playPositionMs,
            :playDeltaMs, :playBuffer, :trafficBytes, :endReason, :farthestMs, :errorStatus, :attrs
        )
        on conflict (id) do nothing
        """;

    /** where not exists 尾巴：FROM 片段由方言注入（AnalyticsDialect#notExistsFromClause）。 */
    private static final String NOT_EXISTS_TAIL =
        "where not exists (select 1 from analytics_event x where x.id = :id)";

    private static final String INSERT_COLUMNS = """
        insert into analytics_event (
            id, site_id, event_type, occurred_at, session_id, visitor_id,
            session_started_at, session_seq, member_id, ip_hash, ip_masked,
            region_province, region_city, server_node, route_path, title,
            content_id, content_type_id, media_asset_id, folder_id,
            referer_host, source_type, search_keyword, utm_source, utm_medium, utm_campaign,
            os, browser, browser_version, device_type, language, screen,
            duration_ms, is_bot, is_spa_nav, play_session_id, play_position_ms,
            play_delta_ms, play_buffer, traffic_bytes, end_reason, farthest_ms, error_status, attrs
        ) select
            :id, :siteId, :eventType, :occurredAt, :sessionId, :visitorId,
            :sessionStartedAt, :sessionSeq, :memberId, :ipHash, :ipMasked,
            :regionProvince, :regionCity, :serverNode, :routePath, :title,
            :contentId, :contentTypeId, :mediaAssetId, :folderId,
            :refererHost, :sourceType, :searchKeyword, :utmSource, :utmMedium, :utmCampaign,
            :os, :browser, :browserVersion, :deviceType, :language, :screen,
            :durationMs, :isBot, :isSpaNav, :playSessionId, :playPositionMs,
            :playDeltaMs, :playBuffer, :trafficBytes, :endReason, :farthestMs, :errorStatus, :attrs
        """;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public List<AnalyticsEventEntity> insertIgnoreBatch(List<AnalyticsEventEntity> events) {
        if (events == null || events.isEmpty()) return List.of();
        List<AnalyticsEventEntity> inserted = new ArrayList<>(events.size());
        var query = em.createNativeQuery(dialect.supportsOnConflict()
            ? INSERT_SQL
            : INSERT_COLUMNS + dialect.notExistsFromClause() + NOT_EXISTS_TAIL);
        for (AnalyticsEventEntity e : events) {
            query.setParameter("id", e.getId());
            query.setParameter("siteId", e.getSiteId());
            query.setParameter("eventType", e.getEventType());
            query.setParameter("occurredAt", Timestamp.from(e.getOccurredAt()));
            query.setParameter("sessionId", e.getSessionId());
            query.setParameter("visitorId", e.getVisitorId());
            query.setParameter("sessionStartedAt", e.getSessionStartedAt() == null ? null : Timestamp.from(e.getSessionStartedAt()));
            query.setParameter("sessionSeq", e.getSessionSeq());
            query.setParameter("memberId", e.getMemberId());
            query.setParameter("ipHash", e.getIpHash());
            query.setParameter("ipMasked", e.getIpMasked());
            query.setParameter("regionProvince", e.getRegionProvince());
            query.setParameter("regionCity", e.getRegionCity());
            query.setParameter("serverNode", e.getServerNode());
            query.setParameter("routePath", e.getRoutePath());
            query.setParameter("title", e.getTitle());
            query.setParameter("contentId", e.getContentId());
            query.setParameter("contentTypeId", e.getContentTypeId());
            query.setParameter("mediaAssetId", e.getMediaAssetId());
            query.setParameter("folderId", e.getFolderId());
            query.setParameter("refererHost", e.getRefererHost());
            query.setParameter("sourceType", e.getSourceType());
            query.setParameter("searchKeyword", e.getSearchKeyword());
            query.setParameter("utmSource", e.getUtmSource());
            query.setParameter("utmMedium", e.getUtmMedium());
            query.setParameter("utmCampaign", e.getUtmCampaign());
            query.setParameter("os", e.getOs());
            query.setParameter("browser", e.getBrowser());
            query.setParameter("browserVersion", e.getBrowserVersion());
            query.setParameter("deviceType", e.getDeviceType());
            query.setParameter("language", e.getLanguage());
            query.setParameter("screen", e.getScreen());
            query.setParameter("durationMs", e.getDurationMs());
            query.setParameter("isBot", e.isBot());
            query.setParameter("isSpaNav", e.isSpaNav());
            query.setParameter("playSessionId", e.getPlaySessionId());
            query.setParameter("playPositionMs", e.getPlayPositionMs());
            query.setParameter("playDeltaMs", e.getPlayDeltaMs());
            query.setParameter("playBuffer", e.getPlayBuffer());
            query.setParameter("trafficBytes", e.getTrafficBytes());
            query.setParameter("endReason", e.getEndReason());
            query.setParameter("farthestMs", e.getFarthestMs());
            query.setParameter("errorStatus", e.getErrorStatus());
            query.setParameter("attrs", e.getAttrs());
            // executeUpdate 返回 1 = 新插入；0 = 冲突跳过（重放），重放事件不得触发派生副作用
            if (query.executeUpdate() == 1) {
                inserted.add(e);
            }
        }
        em.flush();
        em.clear();
        return inserted;
    }

    @Override
    @Transactional(readOnly = true)
    public OnlineSnapshot onlineSnapshot(UUID siteId, Instant since, String serverNode, boolean globalAcrossNodes) {
        boolean global = globalAcrossNodes || serverNode == null || serverNode.isBlank();
        Query online = em.createNativeQuery("""
            select count(distinct visitor_id) from analytics_event
            where site_id = :siteId and occurred_at >= :since and event_type in ('PAGEVIEW','VIDEO_HEARTBEAT','DWELL')
            """ + (global ? "" : " and server_node = :serverNode"));
        Query watching = em.createNativeQuery("""
            select count(distinct visitor_id) from analytics_event
            where site_id = :siteId and occurred_at >= :since and event_type = 'VIDEO_HEARTBEAT'
            """ + (global ? "" : " and server_node = :serverNode"));
        bind(online, siteId, since, serverNode, global);
        bind(watching, siteId, since, serverNode, global);
        return new OnlineSnapshot(
            ((Number) online.getSingleResult()).intValue(),
            ((Number) watching.getSingleResult()).intValue());
    }

    private void bind(Query q, UUID siteId, Instant since, String serverNode, boolean global) {
        q.setParameter("siteId", siteId);
        q.setParameter("since", Timestamp.from(since));
        if (!global) q.setParameter("serverNode", serverNode);
    }
}
