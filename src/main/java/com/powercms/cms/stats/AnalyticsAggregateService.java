package com.powercms.cms.stats;

import com.powercms.cms.stats.repo.AnalyticsReportRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 聚合服务（FR-STAT-010/017/068、NFR-STAT-006）：
 * - 分钟快照：全局 __ALL__ 行（跨节点去重）与节点行；
 * - 日聚合：站点日汇总 + 去重基表（visitor/ip daily）；
 * - 播放会话心跳超时收口。
 * 全部 SQL 幂等（merge/upsert），可安全重算（NFR-STAT-003）。
 */
@Service
public class AnalyticsAggregateService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsAggregateService.class);
    public static final String GLOBAL_NODE = "__ALL__";

    @PersistenceContext
    private EntityManager em;

    private final AnalyticsDialect dialect;

    /**
     * 自代理（ObjectProvider 延迟解析，避免构造期循环引用）：循环内逐站点调用 aggregateDay
     * 必须经代理，否则 REQUIRES_NEW 事务不生效，executeUpdate 抛 TransactionRequiredException
     * （自调用绕过 Spring 代理；曾导致凌晨日聚合任务整体失败、报表 T+1 仍为空）。
     *
     * <p>注意不能用 {@code @Lazy} 构造器注入：原生镜像（GraalVM native-image）下
     * @Lazy 注入会在运行期生成 CGLIB 懒解析代理，与 AOT 预生成的代理类冲突，
     * 首启即抛 {@code ClassCastException: SerializableNoOp cannot be cast to Dispatcher}
     * （见 docs/native-image.md）；ObjectProvider.getObject() 运行期只解析既有代理 Bean，
     * 不生成新类，JVM 与原生镜像行为一致。
     */
    private final ObjectProvider<AnalyticsAggregateService> self;

    public AnalyticsAggregateService(AnalyticsDialect dialect, ObjectProvider<AnalyticsAggregateService> self) {
        this.dialect = dialect;
        this.self = self;
    }

    /** 分钟快照：全局行跨节点去重，严禁节点求和（FR-STAT-106）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeGlobalMinuteSnapshot(Instant minute) {
        List<Object[]> rows = em.createNativeQuery("""
                select site_id,
                       count(distinct case when event_type in ('PAGEVIEW','VIDEO_HEARTBEAT','DWELL') then visitor_id end) as online_count,
                       count(distinct case when event_type = 'VIDEO_HEARTBEAT' then visitor_id end) as watching_count
                from analytics_event
                where occurred_at >= :since and occurred_at < :until
                group by site_id
                """)
            .setParameter("since", Timestamp.from(minute.minusSeconds(300)))
            .setParameter("until", Timestamp.from(minute))
            .getResultList();
        for (Object[] row : rows) {
            UUID siteId = AnalyticsReportRepository.asUuid(row[0]);
            upsertMinute(siteId, GLOBAL_NODE, minute, ((Number) row[1]).intValue(), ((Number) row[2]).intValue());
        }
    }

    /** 分钟快照：各采集节点行（节点视角，仅容量与排查用，FR-STAT-067/106）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeNodeMinuteSnapshots(Instant minute) {
        List<Object[]> rows = em.createNativeQuery("""
                select site_id, server_node,
                       count(distinct case when event_type in ('PAGEVIEW','VIDEO_HEARTBEAT','DWELL') then visitor_id end) as online_count,
                       count(distinct case when event_type = 'VIDEO_HEARTBEAT' then visitor_id end) as watching_count
                from analytics_event
                where occurred_at >= :since and occurred_at < :until and server_node is not null
                group by site_id, server_node
                """)
            .setParameter("since", Timestamp.from(minute.minusSeconds(300)))
            .setParameter("until", Timestamp.from(minute))
            .getResultList();
        for (Object[] row : rows) {
            UUID siteId = AnalyticsReportRepository.asUuid(row[0]);
            String node = (String) row[1];
            upsertMinute(siteId, node, minute, ((Number) row[2]).intValue(), ((Number) row[3]).intValue());
        }
    }

    private void upsertMinute(UUID siteId, String node, Instant minute, int online, int watching) {
        // update-then-insert 可移植写法（H2 2.x 无 ON CONFLICT）：分钟快照仅聚合锁持有者单写者，无并发覆盖
        int updated = em.createNativeQuery("""
                update analytics_online_minute set online_count = :online, watching_count = :watching
                where site_id = :siteId and server_node = :node and minute_bucket = :minute
                """)
            .setParameter("siteId", siteId)
            .setParameter("node", node)
            .setParameter("minute", Timestamp.from(minute))
            .setParameter("online", online)
            .setParameter("watching", watching)
            .executeUpdate();
        if (updated == 0) {
            em.createNativeQuery("""
                    insert into analytics_online_minute (site_id, server_node, minute_bucket, online_count, watching_count)
                    values (:siteId, :node, :minute, :online, :watching)
                    """)
                .setParameter("siteId", siteId)
                .setParameter("node", node)
                .setParameter("minute", Timestamp.from(minute))
                .setParameter("online", online)
                .setParameter("watching", watching)
                .executeUpdate();
        }
    }

    /** 日聚合 + 去重基表（FR-STAT-017）：statDate 为站点时区日期。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void aggregateDay(UUID siteId, LocalDate statDate, ZoneId siteZone) {
        Instant from = statDate.atStartOfDay(siteZone).toInstant();
        Instant to = statDate.plusDays(1).atStartOfDay(siteZone).toInstant();
        upsertSiteDaily(siteId, statDate, from, to);
        upsertDedupBase(siteId, statDate, from, to);
        aggregateHourly(siteId, statDate, from, to);
        aggregateFolderDaily(siteId, statDate, from, to);
        aggregateContentDaily(siteId, statDate, from, to);
        aggregateContentTypeDaily(siteId, statDate, from, to);
        aggregateAssetDaily(siteId, statDate, from, to);
        aggregateClientDaily(siteId, statDate, from, to);
        aggregateSourceDaily(siteId, statDate, from, to);
        aggregateSearchDaily(siteId, statDate, from, to);
        aggregateCustomDaily(siteId, statDate, from, to);
        aggregateEntryExitDaily(siteId, statDate, from, to);
        aggregateDepthFrequencyDaily(siteId, statDate, from, to);
    }

    public void aggregateYesterday(Instant now) {
        // 站点时区"昨天"按各站点 timezone 逐一处理；实现取全部活跃站点循环
        List<Object[]> sites = em.createNativeQuery("select id, timezone from site where status in ('ACTIVE','FROZEN')")
            .getResultList();
        for (Object[] row : sites) {
            UUID siteId = AnalyticsReportRepository.asUuid(row[0]);
            ZoneId zone = row[1] == null ? ZoneOffset.UTC : ZoneId.of((String) row[1]);
            LocalDate yesterday = LocalDate.now(zone).minusDays(1);
            self.getObject().aggregateDay(siteId, yesterday, zone);
        }
    }

    /**
     * 日内增量聚合（当日，站点时区）：与日任务同口径的 delete+insert 幂等重算
     * （NFR-STAT-003），供日内任务高频重跑，让当日维度报表（客户端/来源/忠诚度等）
     * 与概览/趋势提前可见；凌晨日任务仍负责 T+1 最终口径（含跨日去重基表收口）。
     */
    public void aggregateToday(Instant now) {
        List<Object[]> sites = em.createNativeQuery("select id, timezone from site where status in ('ACTIVE','FROZEN')")
            .getResultList();
        for (Object[] row : sites) {
            UUID siteId = AnalyticsReportRepository.asUuid(row[0]);
            ZoneId zone = row[1] == null ? ZoneOffset.UTC : ZoneId.of((String) row[1]);
            self.getObject().aggregateTodayForSite(siteId, zone);
        }
    }

    /** 单站点当日聚合：statDate 取站点时区的「今天」，窗口覆盖至今（to 为次日零点）。 */
    public void aggregateTodayForSite(UUID siteId, ZoneId zone) {
        self.getObject().aggregateDay(siteId, LocalDate.now(zone), zone);
    }

    private void upsertSiteDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        // 行骨架 update-then-insert 幂等插入 + 数值回填（H2 2.x 无 ON CONFLICT，双库通用）
        int updated = em.createNativeQuery("""
                update analytics_site_daily set pv = pv where site_id = :siteId and stat_date = :statDate
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .executeUpdate();
        if (updated == 0) {
            em.createNativeQuery("""
                    insert into analytics_site_daily (site_id, stat_date)
                    values (:siteId, :statDate)
                    """)
                .setParameter("siteId", siteId)
                .setParameter("statDate", java.sql.Date.valueOf(statDate))
                .executeUpdate();
        }
        em.createNativeQuery("""
                update analytics_site_daily t set
                    pv = (select count(*) from analytics_event e
                          where e.site_id = t.site_id and e.event_type = 'PAGEVIEW'
                            and e.occurred_at >= :from and e.occurred_at < :to),
                    uv = (select count(distinct e.visitor_id) from analytics_event e
                          where e.site_id = t.site_id and e.occurred_at >= :from and e.occurred_at < :to),
                    ip_count = (select count(distinct e.ip_hash) from analytics_event e
                          where e.site_id = t.site_id and e.ip_hash is not null
                            and e.occurred_at >= :from and e.occurred_at < :to),
                    session_count = (select count(distinct e.session_id) from analytics_event e
                          where e.site_id = t.site_id and e.occurred_at >= :from and e.occurred_at < :to)
                where t.site_id = :siteId and t.stat_date = :statDate
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    private void upsertDedupBase(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        // 去重基表（FR-STAT-017）：visitor/ip 每日行，insert-select not exists 幂等（H2 2.x 无 ON CONFLICT）
        em.createNativeQuery("""
                insert into analytics_visitor_daily (site_id, stat_date, visitor_hash)
                select distinct e.site_id, cast(:statDate as date), e.visitor_id
                from analytics_event e
                where e.site_id = :siteId and e.occurred_at >= :from and e.occurred_at < :to
                  and not exists (
                    select 1 from analytics_visitor_daily v
                    where v.site_id = e.site_id and v.stat_date = cast(:statDate as date) and v.visitor_hash = e.visitor_id)
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
        em.createNativeQuery("""
                insert into analytics_ip_daily (site_id, stat_date, ip_hash)
                select distinct e.site_id, cast(:statDate as date), e.ip_hash
                from analytics_event e
                where e.site_id = :siteId and e.ip_hash is not null
                  and e.occurred_at >= :from and e.occurred_at < :to
                  and not exists (
                    select 1 from analytics_ip_daily i
                    where i.site_id = e.site_id and i.stat_date = cast(:statDate as date) and i.ip_hash = e.ip_hash)
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /**
     * 维度日聚合统一采用 delete+insert 重算式（NFR-STAT-003 幂等可重跑）：
     * 聚合任务在 advisory lock 下单写者执行，事务内 delete+insert 等价 upsert 且双库可移植。
     */

    /** 时段分布 0-23 时（FR-STAT-012）。 */
    private void aggregateHourly(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_hourly", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_hourly (site_id, stat_date, hour_of_day, pv, uv, ip_count)
                select site_id, cast(:statDate as date),
                       extract(hour from occurred_at),
                       count(case when event_type = 'PAGEVIEW' then 1 end),
                       count(distinct visitor_id),
                       count(distinct ip_hash)
                from analytics_event
                where site_id = :siteId and occurred_at >= :from and occurred_at < :to
                group by site_id, extract(hour from occurred_at)
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 栏目日聚合（FR-STAT-020/021）：folder_id 由事件 enrichment 快照，栏目改名/移动不影响历史。 */
    private void aggregateFolderDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_folder_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_folder_daily (site_id, folder_id, stat_date, pv, uv, ip_count, session_count)
                select site_id, folder_id, cast(:statDate as date),
                       count(case when event_type = 'PAGEVIEW' then 1 end),
                       count(distinct visitor_id),
                       count(distinct ip_hash),
                       count(distinct session_id)
                from analytics_event
                where site_id = :siteId and folder_id is not null
                  and occurred_at >= :from and occurred_at < :to
                group by site_id, folder_id
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 内容日聚合（FR-STAT-030）：pv/uv/play_count 支撑总排行永久累计。 */
    private void aggregateContentDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_content_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_content_daily (site_id, content_id, content_type_id, stat_date, pv, uv, play_count, avg_duration_ms)
                select site_id, content_id,
                       -- uuid 列取最大：原生 uuid 库（H2/PG/金仓）PG 无 max(uuid)，先按文本取最大再转回；
                       -- varchar(36) 库（MySQL/达梦）直接 max。片段见 AnalyticsDialect#maxContentTypeId。
                       """ + dialect.maxContentTypeId() + """
                       ,cast(:statDate as date),
                       count(case when event_type = 'PAGEVIEW' then 1 end),
                       count(distinct visitor_id),
                       count(case when event_type = 'VIDEO_PLAY' then 1 end),
                       """ + dialect.castBigint("avg(case when event_type = 'DWELL' then duration_ms end)") + """
                from analytics_event
                where site_id = :siteId and content_id is not null
                  and occurred_at >= :from and occurred_at < :to
                group by site_id, content_id
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 内容类型日聚合（FR-STAT-031）。 */
    private void aggregateContentTypeDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_content_type_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_content_type_daily (site_id, content_type_id, stat_date, pv, uv)
                select site_id, content_type_id, cast(:statDate as date),
                       count(case when event_type = 'PAGEVIEW' then 1 end),
                       count(distinct visitor_id)
                from analytics_event
                where site_id = :siteId and content_type_id is not null
                  and occurred_at >= :from and occurred_at < :to
                group by site_id, content_type_id
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 媒体资产日聚合（FR-STAT-009）。 */
    private void aggregateAssetDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_asset_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_asset_daily (site_id, media_asset_id, stat_date, play_count, play_session_count, watched_ms_total, traffic_bytes_total)
                select site_id, media_asset_id, cast(:statDate as date),
                       count(case when event_type = 'VIDEO_PLAY' then 1 end),
                       count(distinct play_session_id),
                       coalesce(sum(play_delta_ms), 0),
                       coalesce(sum(traffic_bytes), 0)
                from analytics_event
                where site_id = :siteId and media_asset_id is not null
                  and occurred_at >= :from and occurred_at < :to
                group by site_id, media_asset_id
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 客户端五维日聚合（FR-STAT-050）：dimension ∈ os/browser/language/screen/device。 */
    private void aggregateClientDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_client_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_client_daily (site_id, stat_date, dimension, dimension_value, pv, uv)
                select site_id, cast(:statDate as date), dimension, dim_value,
                       count(case when event_type = 'PAGEVIEW' then 1 end),
                       count(distinct visitor_id)
                from (
                    select site_id, event_type, visitor_id, 'os' as dimension, os as dim_value
                        from analytics_event where site_id = :siteId and occurred_at >= :from and occurred_at < :to
                    union all
                    select site_id, event_type, visitor_id, 'browser', browser
                        from analytics_event where site_id = :siteId and occurred_at >= :from and occurred_at < :to
                    union all
                    select site_id, event_type, visitor_id, 'language', language
                        from analytics_event where site_id = :siteId and occurred_at >= :from and occurred_at < :to
                    union all
                    select site_id, event_type, visitor_id, 'screen', screen
                        from analytics_event where site_id = :siteId and occurred_at >= :from and occurred_at < :to
                    union all
                    select site_id, event_type, visitor_id, 'device', device_type
                        from analytics_event where site_id = :siteId and occurred_at >= :from and occurred_at < :to
                ) d
                where d.dim_value is not null
                group by site_id, d.dimension, d.dim_value
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 来源日聚合（FR-STAT-040/041）。detail 为主键成分不可 NULL，无明细来源用空串占位。 */
    private void aggregateSourceDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_source_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_source_daily (site_id, stat_date, source_type, detail, pv, uv)
                select site_id, cast(:statDate as date), source_type,
                       case when source_type in ('SEARCH_ENGINE','REFERRAL','CAMPAIGN')
                            then coalesce(referer_host, '未知') else '' end,
                       count(case when event_type = 'PAGEVIEW' then 1 end),
                       count(distinct visitor_id)
                from analytics_event
                where site_id = :siteId and occurred_at >= :from and occurred_at < :to
                group by site_id, source_type,
                         case when source_type in ('SEARCH_ENGINE','REFERRAL','CAMPAIGN')
                            then coalesce(referer_host, '未知') else '' end
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 站内搜索词日聚合（FR-STAT-042）。 */
    private void aggregateSearchDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_search_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_search_daily (site_id, stat_date, keyword_hash, keyword_masked, search_count, uv, zero_hit_count, click_count)
                select site_id, cast(:statDate as date),
                       coalesce(ip_hash, concat('kw-', """ + dialect.castVarchar("search_keyword", 200) + """
                       )),
                       search_keyword,
                       count(*),
                       count(distinct visitor_id),
                       count(case when coalesce(duration_ms, 0) = 0 then 1 end),
                       0
                from analytics_event
                where site_id = :siteId and event_type = 'SEARCH' and search_keyword is not null
                  and occurred_at >= :from and occurred_at < :to
                group by site_id, coalesce(ip_hash, concat('kw-', """ + dialect.castVarchar("search_keyword", 200) + """
                )), search_keyword
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 自定义事件日聚合（FR-STAT-097）。 */
    private void aggregateCustomDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_custom_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_custom_daily (site_id, stat_date, event_name, attr_key, attr_value, event_count, uv)
                select site_id, cast(:statDate as date), route_path, null, null, count(*), count(distinct visitor_id)
                from analytics_event
                where site_id = :siteId and event_type = 'CUSTOM' and route_path is not null
                  and occurred_at >= :from and occurred_at < :to
                group by site_id, route_path
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 入口/退出页日聚合（FR-STAT-014）。 */
    private void aggregateEntryExitDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_entry_daily", "site_id", siteId, "stat_date", statDate);
        deleteByDate("analytics_exit_daily", "site_id", siteId, "stat_date", statDate);
        // 会话首事件时间：窗口库用 max(...) over (partition by ...)；MySQL 5.5/5.6/5.7
        // 无窗口函数（1064 实测），改相关子查询——同谓词过滤后取会话 max(occurred_at)，
        // session_id not null，等价于窗口分区最大值。
        String firstEventSelect = dialect.supportsWindowFunctions()
            ? """
                select e.*, max(e.occurred_at) over (partition by e.site_id, e.session_id) first_event
                from analytics_event e
                where e.site_id = :siteId and e.event_type = 'PAGEVIEW' and e.route_path is not null
                  and e.occurred_at >= :from and e.occurred_at < :to
                """
            : """
                select e.site_id, e.session_id, e.session_seq, e.route_path, e.occurred_at,
                       (select max(e2.occurred_at) from analytics_event e2
                         where e2.site_id = e.site_id and e2.session_id = e.session_id
                           and e2.event_type = 'PAGEVIEW' and e2.route_path is not null
                           and e2.occurred_at >= :from and e2.occurred_at < :to) as first_event
                from analytics_event e
                where e.site_id = :siteId and e.event_type = 'PAGEVIEW' and e.route_path is not null
                  and e.occurred_at >= :from and e.occurred_at < :to
                """;
        em.createNativeQuery("""
                insert into analytics_entry_daily (site_id, stat_date, route_path, session_count)
                select site_id, cast(:statDate as date), first_path, count(*)
                from (
                    select site_id, session_id,
                           max(case when session_seq = 1 or session_seq is null and occurred_at = first_event then route_path end) first_path
                    from (
            """ + firstEventSelect + """
                    ) x
                    group by site_id, session_id
                ) y
                where first_path is not null
                group by site_id, first_path
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
        em.createNativeQuery("""
                insert into analytics_exit_daily (site_id, stat_date, route_path, session_count)
                select site_id, cast(:statDate as date), route_path, count(distinct session_id)
                from analytics_event e
                where site_id = :siteId and event_type = 'PAGEVIEW' and route_path is not null
                  and occurred_at >= :from and occurred_at < :to
                  and occurred_at = (select max(e2.occurred_at) from analytics_event e2
                                     where e2.site_id = e.site_id and e2.session_id = e.session_id
                                       and e2.event_type = 'PAGEVIEW')
                group by site_id, route_path
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    /** 访问深度/频度日聚合（FR-STAT-060）：两个独立分布。 */
    private void aggregateDepthFrequencyDaily(UUID siteId, LocalDate statDate, Instant from, Instant to) {
        deleteByDate("analytics_depth_daily", "site_id", siteId, "stat_date", statDate);
        deleteByDate("analytics_frequency_daily", "site_id", siteId, "stat_date", statDate);
        em.createNativeQuery("""
                insert into analytics_depth_daily (site_id, stat_date, depth_bucket, session_count)
                select site_id, cast(:statDate as date),
                       case when page_count between 1 and 2 then '1-2'
                            when page_count between 3 and 5 then '3-5'
                            when page_count between 6 and 9 then '6-9'
                            else '10+' end,
                       count(*)
                from (
                    select site_id, session_id, count(*) page_count
                    from analytics_event
                    where site_id = :siteId and event_type = 'PAGEVIEW'
                      and occurred_at >= :from and occurred_at < :to
                    group by site_id, session_id
                ) s
                group by site_id,
                       case when page_count between 1 and 2 then '1-2'
                            when page_count between 3 and 5 then '3-5'
                            when page_count between 6 and 9 then '6-9'
                            else '10+' end
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
        // 频度：按访客 30 分钟间隔切分"访问"，统计访问次数分布（FR-STAT-060 独立分布）
        // 前序访问时间：窗口库用 lag(...) over (...)；MySQL 5.5/5.6/5.7 无窗口函数
        // （1064 实测），改相关子查询取同访客前一行（occurred_at 降序；5.5 datetime
        // 无小数秒同秒并列以 id 断序，保证每行恰有一个前序值/NULL）。
        String prevVisitSelect = dialect.supportsWindowFunctions()
            ? """
                select visitor_id, occurred_at,
                       lag(occurred_at) over (partition by visitor_id order by occurred_at) prev_at
                from analytics_event
                where site_id = :siteId and event_type = 'PAGEVIEW'
                  and occurred_at >= :from and occurred_at < :to
                """
            : """
                select a.visitor_id, a.occurred_at,
                       (select max(a2.occurred_at) from analytics_event a2
                         where a2.visitor_id = a.visitor_id
                           and a2.site_id = :siteId and a2.event_type = 'PAGEVIEW'
                           and a2.occurred_at >= :from and a2.occurred_at < :to
                           and (a2.occurred_at < a.occurred_at
                                or (a2.occurred_at = a.occurred_at and a2.id < a.id))) as prev_at
                from analytics_event a
                where a.site_id = :siteId and a.event_type = 'PAGEVIEW'
                  and a.occurred_at >= :from and a.occurred_at < :to
                """;
        em.createNativeQuery("""
                insert into analytics_frequency_daily (site_id, stat_date, frequency_bucket, visitor_count)
                select
                """ + dialect.castUuid(":siteId") + """
                ,cast(:statDate as date),
                       case when visit_count = 1 then '1'
                            when visit_count between 2 and 3 then '2-3'
                            when visit_count between 4 and 7 then '4-7'
                            else '8+' end,
                       count(*)
                from (
                    select visitor_id, count(*) visit_count
                    from (
            """ + prevVisitSelect + """
                    ) a
                    where prev_at is null or
                    """ + dialect.visitGapMinutes("occurred_at", "prev_at", 30) + """
                    group by visitor_id
                ) v
                group by case when visit_count = 1 then '1'
                            when visit_count between 2 and 3 then '2-3'
                            when visit_count between 4 and 7 then '4-7'
                            else '8+' end
                """)
            .setParameter("siteId", siteId)
            .setParameter("statDate", java.sql.Date.valueOf(statDate))
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .executeUpdate();
    }

    private void deleteByDate(String table, String siteCol, UUID siteId, String dateCol, LocalDate date) {
        em.createNativeQuery("delete from " + table + " where " + siteCol + " = :siteId and " + dateCol + " = :date")
            .setParameter("siteId", siteId)
            .setParameter("date", java.sql.Date.valueOf(date))
            .executeUpdate();
    }
}
