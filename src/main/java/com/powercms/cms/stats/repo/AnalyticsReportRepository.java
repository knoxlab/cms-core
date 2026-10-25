package com.powercms.cms.stats.repo;

import com.powercms.cms.stats.AnalyticsDialect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 维度报表查询（FR-STAT-009/012/014/020~022/030~033/040~042/050/060/065）：
 * 全部只读，优先读日聚合表（支撑总排行永久累计），URL 排行与最近访问直查明细（90 天窗口）。 */
@Service
public class AnalyticsReportRepository {

    @PersistenceContext
    private EntityManager em;

    private final AnalyticsDialect dialect;

    public AnalyticsReportRepository(AnalyticsDialect dialect) {
        this.dialect = dialect;
    }

    // ---------- 时段分布（FR-STAT-012）----------

    public record HourRow(int hour, long pv, long uv, long ipCount) {}

    @SuppressWarnings("unchecked")
    public List<HourRow> hourlyDistribution(UUID siteId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select hour_of_day, sum(pv), sum(uv), sum(ip_count)
                from analytics_hourly
                where site_id = :siteId and stat_date between :from and :to
                group by hour_of_day order by hour_of_day
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream().map(r -> new HourRow(((Number) r[0]).intValue(), num(r[1]), num(r[2]), num(r[3]))).toList();
    }

    // ---------- 栏目统计（FR-STAT-020~022）----------

    public record FolderRow(UUID folderId, String folderTitle, String folderPath, UUID parentFolderId,
                            long pv, long uv, long ipCount, long sessionCount, long directPv) {}

    /**
     * 栏目下钻：directPv 为自身直接归属PV；pv 为含全部后代栏目（按 folder path 前缀下钻）的汇总。     * 已删除栏目（聚合有值而 site_folder 无记录）标注 title=null 保留历史（FR-STAT-021）。     */
    @SuppressWarnings("unchecked")
    public List<FolderRow> folderReport(UUID siteId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select f.id, f.title, f.path, f.parent_id,
                       coalesce(sum(agg.pv), 0),
                       coalesce(sum(agg.uv), 0),
                       coalesce(sum(agg.ip_count), 0),
                       coalesce(sum(agg.session_count), 0),
                       coalesce(sum(case when agg.folder_id = f.id then agg.pv else 0 end), 0)
                from site_folder f
                left join analytics_folder_daily agg
                    on agg.site_id = f.site_id and agg.stat_date between :from and :to
                   and (agg.folder_id = f.id
                        or agg.folder_id in (select c.id from site_folder c
                                             where c.site_id = f.site_id and c.path like concat(f.path, '/%')))
                where f.site_id = :siteId
                group by f.id, f.title, f.path, f.parent_id
                order by 5 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream().map(r -> new FolderRow(
            asUuid(r[0]), (String) r[1], (String) r[2], asUuid(r[3]),
            num(r[4]), num(r[5]), num(r[6]), num(r[7]), num(r[8]))).toList();
    }

    /** 已删除栏目但仍有历史聚合（FR-STAT-021 标注）。*/
    @SuppressWarnings("unchecked")
    public List<FolderRow> deletedFolders(UUID siteId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select agg.folder_id, """ + dialect.castVarchar("null", 200) + """
                ,""" + dialect.castVarchar("null", 1000) + """
                ,""" + dialect.castUuid("null") + """
                ,sum(agg.pv), sum(agg.uv), sum(agg.ip_count), sum(agg.session_count),
                       sum(agg.pv)
                from analytics_folder_daily agg
                where agg.site_id = :siteId and agg.stat_date between :from and :to
                  and agg.folder_id not in (select f.id from site_folder f where f.site_id = :siteId)
                group by agg.folder_id
                order by 5 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream().map(r -> new FolderRow(
            asUuid(r[0]), (String) r[1], (String) r[2], asUuid(r[3]),
            num(r[4]), num(r[5]), num(r[6]), num(r[7]), num(r[8]))).toList();
    }

    // ---------- 内容排行（FR-STAT-030/033）----------

    public record ContentRankRow(UUID contentId, String title, String folderTitle, String typeName,
                                 String createdBy, long pv, long uv, long playCount, long avgDurationMs) {}

    /**
     * 内容点击排行。excludeSystemTypes=true 时排除MEDIA 与系统类型（默认排行口径，FR-STAT-030）。     * 总排行（from=聚合表最早日）自上线起累计；聚合表永久保留。     */
    @SuppressWarnings("unchecked")
    public List<ContentRankRow> contentRank(UUID siteId, LocalDate from, LocalDate to,
                                            UUID contentTypeId, String metric,
                                            boolean excludeSystemTypes, int limit) {
        String metricCol = "play".equalsIgnoreCase(metric) ? "sum(c.play_count)" : "sum(c.pv)";
        String typeFilter = contentTypeId != null
            ? " and c.content_type_id = :typeId "
            : (excludeSystemTypes
                ? " and c.content_type_id in (select t.id from content_type t where t.site_id = :siteId and t.is_system = :notSystem and t.object_type <> 'MEDIA') "
                : "");
        var q = em.createNativeQuery("""
                select c.content_id,
                       coalesce(max(i.title), '已删除内容'),
                       max(f.title),
                       max(t.name),
                       max(i.created_by),
                       coalesce(sum(c.pv), 0),
                       coalesce(sum(c.uv), 0),
                       coalesce(sum(c.play_count), 0),
                       coalesce(""" + dialect.castBigint("avg(c.avg_duration_ms)") + """
                       , 0)
                from analytics_content_daily c
                left join content_item i on i.id = c.content_id
                left join site_folder f on f.id = i.folder_id
                left join content_type t on t.id = c.content_type_id
                where c.site_id = :siteId and c.stat_date between :from and :to
                """ + typeFilter + """
                group by c.content_id
                order by """ + " " + metricCol + " desc\n")
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .setMaxResults(Math.min(100, Math.max(1, limit)));
        if (contentTypeId != null) q.setParameter("typeId", contentTypeId);
        // DM：bit 列 = boolean 字面量报错（DmSqlProbe2-A1/C1），绑定方式五库通用（P3 实测）
        if (excludeSystemTypes && contentTypeId == null) q.setParameter("notSystem", false);
        List<Object[]> rows = q.getResultList();
        return rows.stream().map(r -> new ContentRankRow(
            asUuid(r[0]), (String) r[1], (String) r[2], (String) r[3], (String) r[4],
            num(r[5]), num(r[6]), num(r[7]), num(r[8]))).toList();
    }

    /**
     * 单条内容表现（FR-AGT-CONTENT-009 效果回流）：指定内容在窗口内的 PV/UV/播放数与平均播放时长；
     * 与 {@link #contentRank} 同源同口径（analytics_content_daily），窗口内无数据返回 null。
     */
    @SuppressWarnings("unchecked")
    public ContentRankRow contentStats(UUID siteId, UUID contentId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select c.content_id,
                       coalesce(max(i.title), '已删除内容'),
                       max(f.title),
                       max(t.name),
                       max(i.created_by),
                       coalesce(sum(c.pv), 0),
                       coalesce(sum(c.uv), 0),
                       coalesce(sum(c.play_count), 0),
                       coalesce(""" + dialect.castBigint("avg(c.avg_duration_ms)") + """
                       , 0)
                from analytics_content_daily c
                left join content_item i on i.id = c.content_id
                left join site_folder f on f.id = i.folder_id
                left join content_type t on t.id = c.content_type_id
                where c.site_id = :siteId and c.content_id = :contentId
                  and c.stat_date between :from and :to
                group by c.content_id
                """)
            .setParameter("siteId", siteId)
            .setParameter("contentId", contentId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] r = rows.get(0);
        return new ContentRankRow(asUuid(r[0]), (String) r[1], (String) r[2], (String) r[3], (String) r[4],
            num(r[5]), num(r[6]), num(r[7]), num(r[8]));
    }

    /**
     * 窗口内播放数合计（FR-AI-AGENT-005 阅读/播放行为口径）：对 analytics_content_daily.play_count 求和，
     * 与内容排行同一数据源、可加指标直接求和（FR-STAT-013/017）。
     */
    public long playCountTotal(UUID siteId, LocalDate from, LocalDate to) {
        Object row = em.createNativeQuery("""
                select coalesce(sum(play_count), 0)
                from analytics_content_daily
                where site_id = :siteId and stat_date between :from and :to
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getSingleResult();
        return num(row);
    }

    // ---------- 内容类型统计（FR-STAT-031）----------

    public record TypeRow(UUID contentTypeId, String typeName, long pv, long uv) {}

    @SuppressWarnings("unchecked")
    public List<TypeRow> contentTypeReport(UUID siteId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select a.content_type_id, coalesce(max(t.name), '已删除类型'),
                       sum(a.pv), sum(a.uv)
                from analytics_content_type_daily a
                left join content_type t on t.id = a.content_type_id
                where a.site_id = :siteId and a.stat_date between :from and :to
                group by a.content_type_id
                order by 3 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream().map(r -> new TypeRow(asUuid(r[0]), (String) r[1], num(r[2]), num(r[3]))).toList();
    }

    // ---------- URL 排行（FR-STAT-032）：直查明细，path 去查询参数 ----------

    public record UrlRow(String path, long pv, long uv) {}

    @SuppressWarnings("unchecked")
    public List<UrlRow> urlRank(UUID siteId, LocalDate from, LocalDate to, int limit) {
        Instant fromTs = from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        String pos = dialect.positionOf("route_path", "'?'");
        String pathNoQuery = "case when " + pos + " > 0 then substr(route_path, 1, " + pos + " - 1) else route_path end";
        List<Object[]> rows = em.createNativeQuery("""
                select
                  """ + pathNoQuery + """
                  ,count(*), count(distinct visitor_id)
                from analytics_event
                where site_id = :siteId and event_type = 'PAGEVIEW' and route_path is not null
                  and occurred_at >= :fromTs and occurred_at < :toTs
                group by """ + " " + pathNoQuery + " " + """
                order by 2 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("fromTs", Timestamp.from(fromTs))
            .setParameter("toTs", Timestamp.from(toTs))
            .setMaxResults(Math.min(100, Math.max(1, limit)))
            .getResultList();
        return rows.stream().map(r -> new UrlRow((String) r[0], num(r[1]), num(r[2]))).toList();
    }

    // ---------- 来源统计（FR-STAT-040/041）----------

    public record SourceRow(String sourceType, String detail, long pv, long uv) {}

    @SuppressWarnings("unchecked")
    public List<SourceRow> sourceReport(UUID siteId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select source_type, coalesce(detail, ''),
                       sum(pv), sum(uv)
                from analytics_source_daily
                where site_id = :siteId and stat_date between :from and :to
                group by source_type, coalesce(detail, '')
                order by 3 desc, 1 asc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream().map(r -> new SourceRow((String) r[0], (String) r[1], num(r[2]), num(r[3]))).toList();
    }

    // ---------- 站内搜索词（FR-STAT-042）----------

    public record SearchTermRow(String keyword, long searchCount, long uv, long zeroHitCount) {}

    @SuppressWarnings("unchecked")
    public List<SearchTermRow> searchTerms(UUID siteId, LocalDate from, LocalDate to, int limit) {
        List<Object[]> rows = em.createNativeQuery("""
                select max(keyword_masked), sum(search_count), sum(uv), sum(zero_hit_count)
                from analytics_search_daily
                where site_id = :siteId and stat_date between :from and :to
                group by keyword_masked
                order by 2 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .setMaxResults(Math.min(100, Math.max(1, limit)))
            .getResultList();
        return rows.stream().map(r -> new SearchTermRow((String) r[0], num(r[1]), num(r[2]), num(r[3]))).toList();
    }

    // ---------- 客户端统计（FR-STAT-050）----------

    public record ClientRow(String dimensionValue, long pv, long uv) {}

    @SuppressWarnings("unchecked")
    public List<ClientRow> clientReport(UUID siteId, String dimension, LocalDate from, LocalDate to) {
        String dim = switch (dimension == null ? "" : dimension) {
            case "browser" -> "browser";
            case "language" -> "language";
            case "screen" -> "screen";
            case "device" -> "device";
            default -> "os";
        };
        List<Object[]> rows = em.createNativeQuery("""
                select dimension_value, sum(pv), sum(uv)
                from analytics_client_daily
                where site_id = :siteId and dimension = :dim and stat_date between :from and :to
                group by dimension_value
                order by 2 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("dim", dim)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream().map(r -> new ClientRow((String) r[0], num(r[1]), num(r[2]))).toList();
    }

    // ---------- 忠诚度（FR-STAT-060）----------

    public record BucketRow(String bucket, long count) {}

    @SuppressWarnings("unchecked")
    public List<BucketRow> depthReport(UUID siteId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select depth_bucket, sum(session_count)
                from analytics_depth_daily
                where site_id = :siteId and stat_date between :from and :to
                group by depth_bucket order by depth_bucket
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream().map(r -> new BucketRow((String) r[0], num(r[1]))).toList();
    }

    @SuppressWarnings("unchecked")
    public List<BucketRow> frequencyReport(UUID siteId, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                select frequency_bucket, sum(visitor_count)
                from analytics_frequency_daily
                where site_id = :siteId and stat_date between :from and :to
                group by frequency_bucket order by frequency_bucket
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .getResultList();
        return rows.stream().map(r -> new BucketRow((String) r[0], num(r[1]))).toList();
    }

    // ---------- 入口/退出页（FR-STAT-014）----------

    public record PageRankRow(String path, long sessionCount) {}

    @SuppressWarnings("unchecked")
    public List<PageRankRow> entryPages(UUID siteId, LocalDate from, LocalDate to, int limit) {
        List<Object[]> rows = em.createNativeQuery("""
                select route_path, sum(session_count)
                from analytics_entry_daily
                where site_id = :siteId and stat_date between :from and :to
                group by route_path order by 2 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .setMaxResults(Math.min(100, Math.max(1, limit)))
            .getResultList();
        return rows.stream().map(r -> new PageRankRow((String) r[0], num(r[1]))).toList();
    }

    @SuppressWarnings("unchecked")
    public List<PageRankRow> exitPages(UUID siteId, LocalDate from, LocalDate to, int limit) {
        List<Object[]> rows = em.createNativeQuery("""
                select route_path, sum(session_count)
                from analytics_exit_daily
                where site_id = :siteId and stat_date between :from and :to
                group by route_path order by 2 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .setMaxResults(Math.min(100, Math.max(1, limit)))
            .getResultList();
        return rows.stream().map(r -> new PageRankRow((String) r[0], num(r[1]))).toList();
    }

    /** 跳出率：单页会话占比（FR-STAT-014，直查会话明细窗口）。*/
    public double bounceRate(UUID siteId, LocalDate from, LocalDate to) {
        Instant fromTs = from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Object row = em.createNativeQuery("""
                select cast(count(case when page_count <= 1 then 1 end) as decimal(18, 6)) / nullif(count(*), 0)
                from (
                    select session_id, count(*) page_count
                    from analytics_event
                    where site_id = :siteId and event_type = 'PAGEVIEW'
                      and occurred_at >= :fromTs and occurred_at < :toTs
                    group by session_id
                ) s
                """)
            .setParameter("siteId", siteId)
            .setParameter("fromTs", Timestamp.from(fromTs))
            .setParameter("toTs", Timestamp.from(toTs))
            .getSingleResult();
        return row instanceof Number n ? n.doubleValue() : 0.0;
    }

    // ---------- 媒体资产排行（FR-STAT-009）----------

    public record AssetRow(UUID mediaAssetId, long playCount, long playSessionCount,
                           long watchedMsTotal, long trafficBytesTotal) {}

    @SuppressWarnings("unchecked")
    public List<AssetRow> assetRank(UUID siteId, LocalDate from, LocalDate to, int limit) {
        List<Object[]> rows = em.createNativeQuery("""
                select media_asset_id, sum(play_count), sum(play_session_count),
                       sum(watched_ms_total), sum(traffic_bytes_total)
                from analytics_asset_daily
                where site_id = :siteId and stat_date between :from and :to
                group by media_asset_id
                order by 2 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Date.valueOf(from))
            .setParameter("to", Date.valueOf(to))
            .setMaxResults(Math.min(100, Math.max(1, limit)))
            .getResultList();
        return rows.stream().map(r -> new AssetRow(asUuid(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]))).toList();
    }

    // ---------- 访问记录（FR-STAT-065）：直查明细，无 PII ----------

    public record RecentVisitRow(Instant occurredAt, String ipMasked, String routePath, String title,
                                 String sourceType, String os, String browser, String visitorId, boolean isNewVisitor) {}

    public record VisitQuery(String q, String searchIn) {}

    /**
     * 访问记录筛选查询：时间区间必选（from/to 为站点时区日期），
     * 可选按 IP（匿名化掩码）、标题、来源、客户端（OS/浏览器）、访客类型（新/回头）过滤。
     * 动态条件仅白名单列名，参数值走绑定，无注入面。
     */
    public VisitsPage visitLog(UUID siteId, LocalDate from, LocalDate to, ZoneId zone,
                               String searchIn, String q, String visitorKind, int page, int size) {
        Instant fromTs = from.atStartOfDay(zone).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(zone).toInstant();

        String searchClause = "";
        if (q != null && !q.isBlank() && searchIn != null) {
            searchClause = switch (searchIn) {
                case "ip" -> " and e.ip_masked like :q ";
                case "title" -> " and e.title like :q ";
                case "source" -> " and e.source_type like :q ";
                case "os" -> " and e.os like :q ";
                case "browser" -> " and e.browser like :q ";
                case "client" -> " and (e.os like :q or e.browser like :q or e.device_type like :q) ";
                default -> "";
            };
        }
        String visitorClause = switch (visitorKind == null ? "" : visitorKind) {
            case "new" -> " and not exists (select 1 from analytics_visitor_daily v where v.site_id = e.site_id and v.visitor_hash = e.visitor_id and v.stat_date < cast(e.occurred_at as date)) ";
            case "returning" -> " and exists (select 1 from analytics_visitor_daily v where v.site_id = e.site_id and v.visitor_hash = e.visitor_id and v.stat_date < cast(e.occurred_at as date)) ";
            default -> "";
        };

        String baseFrom = """
            from analytics_event e
            where e.site_id = :siteId and e.event_type = 'PAGEVIEW'
              and e.occurred_at >= :fromTs and e.occurred_at < :toTs
            """ + searchClause + visitorClause;

        var countQuery = em.createNativeQuery("select count(*) " + baseFrom);
        var rowsQuery = em.createNativeQuery("""
            select e.occurred_at, e.ip_masked, e.route_path, e.title,
                   e.source_type, e.os, e.browser, e.visitor_id,
                   case when exists (
                       select 1 from analytics_visitor_daily v
                       where v.site_id = e.site_id and v.visitor_hash = e.visitor_id
                         and v.stat_date < cast(e.occurred_at as date)) then 1 else 0 end
            """ + baseFrom + """
            order by e.occurred_at desc
            """);

        countQuery.setParameter("siteId", siteId)
            .setParameter("fromTs", Timestamp.from(fromTs))
            .setParameter("toTs", Timestamp.from(toTs));
        rowsQuery.setParameter("siteId", siteId)
            .setParameter("fromTs", Timestamp.from(fromTs))
            .setParameter("toTs", Timestamp.from(toTs));
        if (searchClause.contains(":q")) {
            String like = "%" + q + "%";
            countQuery.setParameter("q", like);
            rowsQuery.setParameter("q", like);
        }
        // 分页交给 JPA setMaxResults/setFirstResult：Hibernate 按方言改写
        // （limit/offset 在达梦/Oracle 不合法，交由 OracleDialect 生成 OFFSET ... FETCH ...）
        int pageSize = Math.min(200, Math.max(1, size));
        rowsQuery.setMaxResults(pageSize).setFirstResult(Math.max(0, page) * pageSize);

        long total = ((Number) countQuery.getSingleResult()).longValue();
        List<RecentVisitRow> rows = new ArrayList<>();
        for (Object[] r : (List<Object[]>) rowsQuery.getResultList()) {
            rows.add(new RecentVisitRow(
                r[0] instanceof Timestamp t ? t.toInstant() : null,
                (String) r[1], (String) r[2], (String) r[3],
                (String) r[4], (String) r[5], (String) r[6], (String) r[7],
                asBool(r[8])));
        }
        return new VisitsPage(total, rows);
    }

    public record VisitsPage(long total, List<RecentVisitRow> rows) {}

    @Deprecated
    public List<RecentVisitRow> recentVisits(UUID siteId, Instant since, int limit) {
        List<Object[]> rows = em.createNativeQuery("""
                select e.occurred_at, e.ip_masked, e.route_path, e.title,
                       e.source_type, e.os, e.browser, e.visitor_id,
                       case when exists (
                           select 1 from analytics_visitor_daily v
                           where v.site_id = e.site_id and v.visitor_hash = e.visitor_id
                             and v.stat_date < cast(e.occurred_at as date)) then 1 else 0 end
                from analytics_event e
                where e.site_id = :siteId and e.event_type = 'PAGEVIEW' and e.occurred_at >= :since
                order by e.occurred_at desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("since", Timestamp.from(since))
            .setMaxResults(Math.min(200, Math.max(1, limit)))
            .getResultList();
        return rows.stream().map(r -> new RecentVisitRow(
            r[0] instanceof Timestamp t ? t.toInstant() : null,
            (String) r[1], (String) r[2], (String) r[3],
                (String) r[4], (String) r[5], (String) r[6], (String) r[7],
                asBool(r[8]))).toList();
    }

    /** 聚合表最早日期（总排行起始标注，FR-STAT-030）。*/
    public LocalDate earliestAggregateDate(UUID siteId) {
        Object row = em.createNativeQuery("""
                select min(stat_date) from analytics_site_daily where site_id = :siteId
                """)
            .setParameter("siteId", siteId)
            .getSingleResult();
        return row instanceof Date d ? d.toLocalDate() : null;
    }

    // ---------- 区域分布（FR-STAT-005）----------

    public record RegionRow(String province, String city, long pv, long uv) {}

    /**
     * 区域分布：直查明细按省份（可选城市）聚合，90 天窗口。
     * province/city 为空时归入「未知地区」，保证图表与表格口径一致。
     */
    @SuppressWarnings("unchecked")
    public List<RegionRow> regionReport(UUID siteId, LocalDate from, LocalDate to, int limit) {
        Instant fromTs = from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<Object[]> rows = em.createNativeQuery("""
                select coalesce(nullif(region_province, ''), '未知地区'),
                       coalesce(nullif(region_city, ''), ''),
                       count(*), count(distinct visitor_id)
                from analytics_event
                where site_id = :siteId and event_type = 'PAGEVIEW'
                  and occurred_at >= :fromTs and occurred_at < :toTs
                group by coalesce(nullif(region_province, ''), '未知地区'),
                         coalesce(nullif(region_city, ''), '')
                order by 3 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("fromTs", Timestamp.from(fromTs))
            .setParameter("toTs", Timestamp.from(toTs))
            .setMaxResults(Math.min(100, Math.max(1, limit)))
            .getResultList();
        return rows.stream().map(r -> new RegionRow((String) r[0], (String) r[1], num(r[2]), num(r[3]))).toList();
    }

    /**
     * H2 原生查询将 uuid 列返回为 byte[]，PostgreSQL 返回 UUID）     * 统一转换，避免调用方逐处判断（FR-STAT-101 双库支持）。     */
    public static UUID asUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        if (value instanceof byte[] bytes) {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(bb.getLong(), bb.getLong());
        }
        if (value instanceof String s && !s.isBlank()) return UUID.fromString(s);
        return null;
    }

    /** exists 投影在各库返回类型不一（Boolean / tinyint 1,0 / integer）：统一归一。 */
    private static boolean asBool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}
