package com.powercms.cms.stats.repo;

import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 统计报表查询（FR-STAT-010/011/068）：日聚合表与分钟快照表的只读查询。
 */
@Service
public class AnalyticsTrendRepository {

    @PersistenceContext
    private EntityManager em;

    public record RangeTotal(long pv, long uv, long ipCount, long sessionCount) {}
    public record DailyRow(LocalDate statDate, long pv, long uv, long ipCount, long sessionCount) {}
    public record MinuteRow(Instant minute, int onlineCount, int watchingCount) {}

    public RangeTotal rangeTotal(UUID siteId, LocalDate from, LocalDate to) {
        // PV/会话为可加指标直接对日聚合求和；
        // UV/IP 为去重指标，跨天必须按去重基表 COUNT(DISTINCT) 精确计算，不得日值相加（FR-STAT-013/017）。
        Object[] row = single(em.createNativeQuery("""
                select coalesce(sum(pv),0),
                       (select count(distinct d.visitor_hash) from analytics_visitor_daily d
                        where d.site_id = :siteId and d.stat_date between :from and :to),
                       (select count(distinct i.ip_hash) from analytics_ip_daily i
                        where i.site_id = :siteId and i.stat_date between :from and :to),
                       coalesce(sum(session_count),0)
                from analytics_site_daily where site_id = :siteId and stat_date between :from and :to
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList());
        return new RangeTotal(num(row[0]), num(row[1]), num(row[2]), num(row[3]));
    }

    /**
     * 回头访客数（FR-STAT-010 回头率分母口径）：范围内访客中，曾在范围开始前的任意日期
     * 出现于去重基表的数量。明细 90 天清理后仍可由永久基表支撑（FR-STAT-017）。
     */
    public long returnVisitorCount(UUID siteId, LocalDate from, LocalDate to) {
        Object row = em.createNativeQuery("""
                select count(distinct d.visitor_hash) from analytics_visitor_daily d
                where d.site_id = :siteId and d.stat_date between :from and :to
                  and exists (
                    select 1 from analytics_visitor_daily h
                    where h.site_id = d.site_id and h.visitor_hash = d.visitor_hash
                      and h.stat_date < :from)
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getSingleResult();
        return num(row);
    }

    @SuppressWarnings("unchecked")
    public List<DailyRow> dailyTrend(UUID siteId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select stat_date, coalesce(sum(pv),0), coalesce(sum(uv),0), coalesce(sum(ip_count),0), coalesce(sum(session_count),0)
                from analytics_site_daily where site_id = :siteId and stat_date between :from and :to
                group by stat_date order by stat_date
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream()
            .map(r -> new DailyRow(((Date) r[0]).toLocalDate(), num(r[1]), num(r[2]), num(r[3]), num(r[4])))
            .toList();
    }

    @SuppressWarnings("unchecked")
    public List<MinuteRow> minuteTrend(UUID siteId, String serverNode, int minutes) {
        Instant since = Instant.now().minusSeconds(minutes * 60L);
        List<Object[]> rows = em.createNativeQuery("""
                select minute_bucket, online_count, watching_count
                from analytics_online_minute
                where site_id = :siteId and server_node = :node and minute_bucket >= :since
                order by minute_bucket
                """)
            .setParameter("siteId", siteId)
            .setParameter("node", serverNode)
            .setParameter("since", Timestamp.from(since))
            .getResultList();
        return rows.stream()
            .map(r -> new MinuteRow(((Timestamp) r[0]).toInstant(), ((Number) r[1]).intValue(), ((Number) r[2]).intValue()))
            .toList();
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private Object[] single(List<Object[]> rows) {
        return rows.isEmpty() ? new Object[]{0, 0, 0, 0} : rows.get(0);
    }
}
