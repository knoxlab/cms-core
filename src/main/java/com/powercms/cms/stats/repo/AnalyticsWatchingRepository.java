package com.powercms.cms.stats.repo;

import com.powercms.cms.stats.AnalyticsDialect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 实时观看列表查询（FR-STAT-069）：在线窗口内视频事件（VIDEO_PLAY / VIDEO_HEARTBEAT /
 * VIDEO_END）按访客取最新一条，支持按内容标题、会员账号（服务端 join，不暴露明文姓名）、
 * IP 哈希前缀（/24 段）、地区检索。仅在线窗口，不扫全表（NFR-STAT-007）。
 *
 * <p>最新一条事件额外带出<b>播放状态与累计量</b>：状态由事件类型 + play_buffer（暂停位）
 * 推导（播放中 / 已暂停 / 已停止，停止原因取会话 end_reason）；流量与观看时长取
 * analytics_play_session 的会话累计（心跳原子累加），经 LEFT JOIN 关联——关联键在
 * uuid 列库（H2/PG/金仓）把 p.id cast 成文本与事件的 varchar play_session_id 比较，
 * 而不是反向把事件值 cast 成 uuid：客户端可伪造 play_session_id，非 uuid 脏值会把
 * 整个列表炸成 500，文本侧比较则天然容忍（脏值不匹配、显示 0）。</p>
 *
 * <p>按访客取最新一条有两条实现，由 {@link AnalyticsDialect#supportsWindowFunctions()}
 * 能力位选择：窗口库（H2/PostgreSQL/金仓/达梦/MySQL 8.x）用 row_number 窗口重写；
 * MySQL 5.5/5.6/5.7 无窗口函数（1064 实测），走两层 group by 等价重写。</p>
 */
@Service
public class AnalyticsWatchingRepository {

    @PersistenceContext
    private EntityManager em;

    private final AnalyticsDialect analyticsDialect;

    public AnalyticsWatchingRepository(AnalyticsDialect analyticsDialect) {
        this.analyticsDialect = analyticsDialect;
    }

    /**
     * @param eventType          最新一条视频事件类型（VIDEO_PLAY / VIDEO_HEARTBEAT / VIDEO_END）
     * @param paused             最新事件的暂停位（play_buffer；PLAY/END 事件为 false）
     * @param endReason          会话结束原因（analytics_play_session.end_reason，未结束为 null）
     * @param sessionTrafficBytes 会话累计流量（心跳原子累加，join 未命中为 0）
     * @param sessionWatchedMs   会话累计观看毫秒（同上）
     */
    public record WatchingRow(
        String visitorId, String memberAccount, String contentTitle, String routePath,
        String ipMasked, String regionProvince, String regionCity,
        String os, String browser, String deviceType,
        long playPositionMs, Instant lastActiveAt, String serverNode,
        String eventType, boolean paused, String endReason,
        long sessionTrafficBytes, long sessionWatchedMs) {}

    public record WatchingPage(long total, List<WatchingRow> rows) {}

    /** 按别名生成 from/join/where（count、窗口版内层、无窗口版三处复用同一谓词）。 */
    private static String baseFrom(String a, String u, boolean hasNode,
                                   String searchIn, boolean hasSearch) {
        return fromJoin(a, u) + wherePart(a, u, hasNode, searchIn, hasSearch);
    }

    /** {@code from analytics_event a left join sys_user u ...}（无窗口版手工拼接 from/where 时用）。 */
    private static String fromJoin(String a, String u) {
        return "from analytics_event " + a + "\n"
            + "left join sys_user " + u + " on " + u + ".id = " + a + ".member_id\n";
    }

    /** 在 {@link #fromJoin} 基础上补播会议话表（取会话累计流量/观看时长/结束原因）。 */
    private String fromJoinWithSession(String a, String u, String p) {
        return fromJoin(a, u)
            + "left join analytics_play_session " + p + " on "
            + analyticsDialect.castVarchar(p + ".id", 36) + " = " + a + ".play_session_id\n";
    }

    /** 观看列表外层投影（两方言路径共用同一列序，行映射按下标对齐）。 */
    private static String rowColumns(String a, String u, String p) {
        // play_buffer 原样带出（PLAY/END 事件为 null），「是否暂停」在行映射处判定，
        // 不在 SQL 里 coalesce 假值：达梦无 false 字面量（mvntest6 实测）。
        return a + ".visitor_id, " + u + ".username, " + a + ".title, " + a + ".route_path,\n"
            + "    " + a + ".ip_masked, " + a + ".region_province, " + a + ".region_city,\n"
            + "    " + a + ".os, " + a + ".browser, " + a + ".device_type,\n"
            + "    coalesce(" + a + ".play_position_ms, 0) as play_position_ms,\n"
            + "    " + a + ".event_type, " + a + ".play_buffer,\n"
            + "    " + p + ".end_reason,\n"
            + "    coalesce(" + p + ".traffic_bytes, 0) as session_traffic,\n"
            + "    coalesce(" + p + ".watched_ms, 0) as session_watched,\n"
            + "    " + a + ".occurred_at, " + a + ".server_node\n";
    }

    /** 站点/类型/窗口 + 节点 + 检索谓词（别名化）。 */
    private static String wherePart(String a, String u, boolean hasNode,
                                    String searchIn, boolean hasSearch) {
        String node = hasNode ? " and " + a + ".server_node = :node " : "";
        String search = "";
        if (hasSearch) {
            search = switch (searchIn == null ? "title" : searchIn) {
                case "user" -> " and " + u + ".username like :q ";
                case "ip" -> " and " + a + ".ip_hash like :q ";
                case "region" -> " and (" + a + ".region_province like :q or " + a + ".region_city like :q) ";
                default -> " and " + a + ".title like :q ";
            };
        }
        // 视频事件全集（起播/心跳/结束）而非仅心跳：观看列表要能显示「已停止」状态，
        // 只取心跳会把刚结束的访客错误标成播放中；在线窗口（occurred_at >= :since）不变。
        return "where " + a + ".site_id = :siteId and " + a
            + ".event_type in ('VIDEO_PLAY', 'VIDEO_HEARTBEAT', 'VIDEO_END') and " + a + ".occurred_at >= :since\n"
            + node + search;
    }

    @SuppressWarnings("unchecked")
    public WatchingPage watching(UUID siteId, Instant windowStart, String serverNode,
                                 String searchIn, String q, int page, int size) {
        boolean hasNode = serverNode != null && !serverNode.isBlank();
        boolean hasSearch = q != null && !q.isBlank();

        String baseE = baseFrom("e", "m", hasNode, searchIn, hasSearch);
        var countQuery = em.createNativeQuery(
            "select count(distinct e.visitor_id) " + baseE);

        String rowsSql;
        if (analyticsDialect.supportsWindowFunctions()) {
            // 按访客取最新一条：row_number 窗口重写（五库通用基线）。
            // 达梦不支持 PG 系 distinct on（DmSqlProbe2-E1 实测本重写通过，distinct on 报 Syntax error）。
            rowsSql = """
                select visitor_id, username, title, route_path,
                    ip_masked, region_province, region_city,
                    os, browser, device_type,
                    play_position_ms, event_type, play_buffer, end_reason,
                    session_traffic, session_watched, occurred_at, server_node
                from (
                    select
                """ + rowColumns("e", "m", "p")
                + "                        , row_number() over (partition by e.visitor_id order by e.occurred_at desc) as rn\n"
                + fromJoinWithSession("e", "m", "p")
                + wherePart("e", "m", hasNode, searchIn, hasSearch)
                + """
                ) dedup
                where dedup.rn = 1
                order by visitor_id, occurred_at desc
                limit :limit offset :offset
                """;
        } else {
            // MySQL 5.5/5.6/5.7 无窗口函数（1064 实测）：两层 group by 取每访客最新一条。
            // 第一层按访客取 max(occurred_at)；第二层在同秒（5.5 datetime 无小数秒，
            // 心跳同秒并列实测可能）并列行里取 max(id) 保证每访客恰返回一行。
            rowsSql = "select\n" + rowColumns("e", "m", "p")
                + fromJoinWithSession("e", "m", "p") + "join (\n"
                + "    select z.visitor_id, max(z.id) as last_id\n"
                + fromJoin("z", "m3")
                + "    join (\n        select y.visitor_id, max(y.occurred_at) as last_at\n"
                + baseFrom("y", "m4", hasNode, searchIn, hasSearch)
                + "        group by y.visitor_id\n"
                + "    ) latest on latest.visitor_id = z.visitor_id and latest.last_at = z.occurred_at\n"
                + wherePart("z", "m3", hasNode, searchIn, hasSearch)
                + "    group by z.visitor_id\n"
                + ") last on last.last_id = e.id\n"
                + wherePart("e", "m", hasNode, searchIn, hasSearch)
                + """
                order by e.visitor_id, e.occurred_at desc
                limit :limit offset :offset
                """;
        }
        var rowsQuery = em.createNativeQuery(rowsSql);

        countQuery.setParameter("siteId", siteId).setParameter("since", Timestamp.from(windowStart));
        rowsQuery.setParameter("siteId", siteId).setParameter("since", Timestamp.from(windowStart));
        if (hasNode) {
            countQuery.setParameter("node", serverNode);
            rowsQuery.setParameter("node", serverNode);
        }
        if (hasSearch) {
            String like = "%" + q + "%";
            countQuery.setParameter("q", like);
            rowsQuery.setParameter("q", like);
        }
        rowsQuery.setParameter("limit", size);
        rowsQuery.setParameter("offset", page * size);

        long total = ((Number) countQuery.getSingleResult()).longValue();
        List<WatchingRow> rows = new ArrayList<>();
        for (Object[] r : (List<Object[]>) rowsQuery.getResultList()) {
            rows.add(new WatchingRow(
                (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                (String) r[4], (String) r[5], (String) r[6],
                (String) r[7], (String) r[8], (String) r[9],
                r[10] instanceof Number n ? n.longValue() : 0,
                r[16] instanceof Timestamp t ? t.toInstant() : null,
                (String) r[17],
                (String) r[11], asBool(r[12]), (String) r[13],
                r[14] instanceof Number n ? n.longValue() : 0,
                r[15] instanceof Number n ? n.longValue() : 0));
        }
        return new WatchingPage(total, rows);
    }

    /** play_buffer 跨库取值归一：H2/PG 为 Boolean，MySQL tinyint 视驱动可能返回 Number。 */
    private static boolean asBool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return false;
    }
}
