package com.powercms.cms.stats.web;

import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.stats.AnalyticsAggregateService;
import com.powercms.cms.stats.AnalyticsEventStore;
import com.powercms.cms.stats.repo.AnalyticsNodeRepository;
import com.powercms.cms.stats.repo.AnalyticsPublishRepository;
import com.powercms.cms.stats.repo.AnalyticsReportRepository;
import com.powercms.cms.stats.repo.AnalyticsTrendRepository;
import com.powercms.cms.stats.repo.AnalyticsWatchingRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统计查询 API（FR-STAT-009/010~016/020~022/030~033/040~042/050/060/065/066~069/107）。
 * 鉴权：`stats` 能力（全部报表/曲线），`stats-admin` 能力（实时观看明细、节点管理、跨用户工作量）。
 * 日期参数均为站点时区口径；总排行起始以聚合表最早日标注（FR-STAT-030）。
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/analytics")
public class AnalyticsQueryController {

    private final AnalyticsEventStore eventStore;
    private final AnalyticsAggregateService aggregateService;
    private final AnalyticsNodeRepository nodeRepo;
    private final AnalyticsTrendRepository trendRepo;
    private final AnalyticsWatchingRepository watchingRepo;
    private final AnalyticsReportRepository reportRepo;
    private final AnalyticsPublishRepository publishRepo;
    private final SiteRepository siteRepo;
    /** OBS-11-04：AI 批量建稿写入的 {@code content_item.created_by} 是用户 ID，展示前解析为用户名。 */
    private final com.powercms.cms.identity.ActorNameResolver actorNames;

    public AnalyticsQueryController(AnalyticsEventStore eventStore,
                                    AnalyticsAggregateService aggregateService,
                                    AnalyticsNodeRepository nodeRepo,
                                    AnalyticsTrendRepository trendRepo,
                                    AnalyticsWatchingRepository watchingRepo,
                                    AnalyticsReportRepository reportRepo,
                                    AnalyticsPublishRepository publishRepo,
                                    SiteRepository siteRepo,
                                    com.powercms.cms.identity.ActorNameResolver actorNames) {
        this.eventStore = eventStore;
        this.aggregateService = aggregateService;
        this.nodeRepo = nodeRepo;
        this.trendRepo = trendRepo;
        this.watchingRepo = watchingRepo;
        this.reportRepo = reportRepo;
        this.publishRepo = publishRepo;
        this.siteRepo = siteRepo;
        this.actorNames = actorNames;
    }

    private ZoneId siteZone(UUID siteId) {
        return siteRepo.findById(siteId)
            .map(SiteEntity::getTimezone)
            .map(tz -> tz == null || tz.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(tz))
            .orElse(ZoneId.of("Asia/Shanghai"));
    }

    public record OverviewResponse(long pv, long uv, long ipCount, long sessionCount,
                                   double returnRate, double wowPv, Long prevPv,
                                   String dataFrom, String metricNote) {}

    /**
     * 综合报告（FR-STAT-010/013/015/016）：范围 PV/UV/IP/会话 + 回头率 + PV 环比。
     * 跨天 UV/IP 按去重基表精确计算（FR-STAT-013）；口径说明随响应返回（FR-STAT-016）。
     */
    @GetMapping("/overview")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public OverviewResponse overview(@PathVariable UUID siteId,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AnalyticsTrendRepository.RangeTotal total = trendRepo.rangeTotal(siteId, from, to);
        // 回头率：范围内回头访客（基表历史已出现）/ UV（FR-STAT-010 术语表口径）
        double returnRate = total.uv() == 0 ? 0
            : (double) trendRepo.returnVisitorCount(siteId, from, to) / total.uv();
        // PV 环比（FR-STAT-015）：紧邻等长基期；基期无数据展示 —（null）
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevFrom = from.minusDays(days), prevTo = from.minusDays(1);
        AnalyticsTrendRepository.RangeTotal prev = trendRepo.rangeTotal(siteId, prevFrom, prevTo);
        Double wowPv = prev.pv() == 0 ? null : (double) (total.pv() - prev.pv()) / prev.pv();
        LocalDate dataFrom = reportRepo.earliestAggregateDate(siteId);
        return new OverviewResponse(total.pv(), total.uv(), total.ipCount(), total.sessionCount(),
            round4(returnRate), wowPv == null ? 0 : round4(wowPv), prev.pv() == 0 ? null : prev.pv(),
            dataFrom == null ? null : dataFrom.toString(), METRIC_NOTE);
    }

    static final String METRIC_NOTE =
        "UV/IP 跨天范围按 analytics_visitor_daily / analytics_ip_daily 基表 COUNT(DISTINCT) 精确去重（FR-STAT-013）；" +
        "回头率=范围内曾于历史访问的访客数÷UV；环比基期为紧邻等长周期，基期无数据显示「—」（FR-STAT-015/016）。";

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    public record TrendPoint(String statDate, long pv, long uv, long ipCount, long sessionCount) {}

    /** 访问趋势（FR-STAT-011）：日粒度 PV/UV/IP/会话。 */
    @GetMapping("/trend")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<TrendPoint> trend(@PathVariable UUID siteId,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return trendRepo.dailyTrend(siteId, from, to).stream()
            .map(r -> new TrendPoint(r.statDate().toString(), r.pv(), r.uv(), r.ipCount(), r.sessionCount()))
            .toList();
    }

    /** 时段分布（FR-STAT-012）：0–23 时 PV/UV/IP。 */
    public record HourPoint(int hour, long pv, long uv, long ipCount) {}

    @GetMapping("/hourly")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<HourPoint> hourly(@PathVariable UUID siteId,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportRepo.hourlyDistribution(siteId, from, to).stream()
            .map(r -> new HourPoint(r.hour(), r.pv(), r.uv(), r.ipCount()))
            .toList();
    }

    /** 栏目下钻报表（FR-STAT-020/021/022）：含子孙汇总与自身直接访问双口径。 */
    @GetMapping("/folders")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public Map<String, Object> folderReport(@PathVariable UUID siteId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var rows = reportRepo.folderReport(siteId, from, to).stream()
            .map(r -> Map.<String, Object>of(
                "folderId", String.valueOf(r.folderId()),
                "title", r.folderTitle() == null ? "已删除栏目" : r.folderTitle(),
                "path", String.valueOf(r.folderPath()),
                "parentFolderId", r.parentFolderId() == null ? "" : r.parentFolderId().toString(),
                "pv", r.pv(), "uv", r.uv(), "ipCount", r.ipCount(),
                "sessionCount", r.sessionCount(), "directPv", r.directPv()))
            .toList();
        var deleted = reportRepo.deletedFolders(siteId, from, to).stream()
            .map(r -> Map.<String, Object>of(
                "folderId", String.valueOf(r.folderId()), "title", "已删除栏目",
                "path", "", "parentFolderId", "",
                "pv", r.pv(), "uv", r.uv(), "ipCount", r.ipCount(),
                "sessionCount", r.sessionCount(), "directPv", r.directPv()))
            .toList();
        return Map.of("rows", rows, "deleted", deleted);
    }

    /**
     * 内容点击排行（FR-STAT-030/033）：metric=pv|play；period=total|month|week（total 自聚合起始累计）。
     * media/system 内容类型仅显式传 contentTypeId 时保留（对齐检索范围约定）。
     */
    @GetMapping("/contents/rank")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public Map<String, Object> contentRank(@PathVariable UUID siteId,
                                           @RequestParam(defaultValue = "total") String period,
                                           @RequestParam(defaultValue = "pv") String metric,
                                           @RequestParam(required = false) UUID contentTypeId,
                                           @RequestParam(defaultValue = "20") int limit,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ZoneId zone = siteZone(siteId);
        LocalDate today = LocalDate.now(zone);
        LocalDate[] range = resolvePeriod(period, from, to, today);
        boolean explicitType = contentTypeId != null;
        var rows = reportRepo.contentRank(siteId, range[0], range[1], contentTypeId, metric,
            !explicitType, limit);
        LocalDate dataFrom = reportRepo.earliestAggregateDate(siteId);
        // OBS-11-04：AI 批量建稿产物 created_by 为用户 ID，展示名统一解析（用户名则原样）
        Map<String, String> actorNamesById = actorNames.resolveAll(
            rows.stream().map(r -> String.valueOf(r.createdBy())).toList());
        return Map.of(
            "from", range[0].toString(), "to", range[1].toString(),
            "totalFrom", dataFrom == null ? "" : dataFrom.toString(),
            "rows", rows.stream().map(r -> Map.<String, Object>of(
                "contentId", String.valueOf(r.contentId()),
                "title", String.valueOf(r.title()),
                "folderTitle", String.valueOf(r.folderTitle()),
                "typeName", String.valueOf(r.typeName()),
                "createdBy", String.valueOf(r.createdBy()),
                "createdByName", actorNamesById.getOrDefault(String.valueOf(r.createdBy()), String.valueOf(r.createdBy())),
                "pv", r.pv(), "uv", r.uv(), "playCount", r.playCount(),
                "avgDurationMs", r.avgDurationMs())).toList());
    }

    private LocalDate[] resolvePeriod(String period, LocalDate from, LocalDate to, LocalDate today) {
        if (from != null && to != null) return new LocalDate[]{from, to};
        return switch (period == null ? "total" : period) {
            case "month" -> new LocalDate[]{today.withDayOfMonth(1), today};
            case "week" -> new LocalDate[]{today.with(java.time.DayOfWeek.MONDAY), today};
            default -> {
                LocalDate earliest = reportRepo.earliestAggregateDate(today == null ? null : null);
                yield new LocalDate[]{earliest == null ? today : earliest, today};
            }
        };
    }

    /** 内容类型统计（FR-STAT-031）。 */
    @GetMapping("/content-types")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> contentTypeReport(@PathVariable UUID siteId,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportRepo.contentTypeReport(siteId, from, to).stream()
            .map(r -> Map.<String, Object>of(
                "contentTypeId", String.valueOf(r.contentTypeId()),
                "typeName", String.valueOf(r.typeName()),
                "pv", r.pv(), "uv", r.uv()))
            .toList();
    }

    /** URL 点击排行（FR-STAT-032）：明细 90 天窗口，path 去查询参数。 */
    @GetMapping("/urls/rank")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> urlRank(@PathVariable UUID siteId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "20") int limit) {
        return reportRepo.urlRank(siteId, from, to, limit).stream()
            .map(r -> Map.<String, Object>of("path", String.valueOf(r.path()), "pv", r.pv(), "uv", r.uv()))
            .toList();
    }

    /** 来源组成（FR-STAT-040/041）。 */
    @GetMapping("/sources")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> sources(@PathVariable UUID siteId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportRepo.sourceReport(siteId, from, to).stream()
            .map(r -> Map.<String, Object>of(
                "sourceType", String.valueOf(r.sourceType()),
                "detail", String.valueOf(r.detail()),
                "pv", r.pv(), "uv", r.uv()))
            .toList();
    }

    /** 站内搜索词排行（FR-STAT-042）。 */
    @GetMapping("/search/terms")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> searchTerms(@PathVariable UUID siteId,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(defaultValue = "20") int limit) {
        return reportRepo.searchTerms(siteId, from, to, limit).stream()
            .map(r -> Map.<String, Object>of(
                "keyword", String.valueOf(r.keyword()),
                "searchCount", r.searchCount(), "uv", r.uv(), "zeroHitCount", r.zeroHitCount()))
            .toList();
    }

    /** 客户端统计（FR-STAT-050）：dimension=os|browser|language|screen|device。 */
    @GetMapping("/clients")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> clients(@PathVariable UUID siteId,
                                             @RequestParam(defaultValue = "os") String dimension,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportRepo.clientReport(siteId, dimension, from, to).stream()
            .map(r -> Map.<String, Object>of(
                "value", String.valueOf(r.dimensionValue()), "pv", r.pv(), "uv", r.uv()))
            .toList();
    }

    /** 忠诚度（FR-STAT-060）：depth=访问深度分布，frequency=访问频度分布。 */
    @GetMapping("/loyalty/{kind}")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> loyalty(@PathVariable UUID siteId, @PathVariable String kind,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<AnalyticsReportRepository.BucketRow> rows =
            "frequency".equalsIgnoreCase(kind)
                ? reportRepo.frequencyReport(siteId, from, to)
                : reportRepo.depthReport(siteId, from, to);
        return rows.stream()
            .map(r -> Map.<String, Object>of("bucket", String.valueOf(r.bucket()), "count", r.count()))
            .toList();
    }

    /** 入口/退出页排行（FR-STAT-014）。 */
    @GetMapping("/pages/{kind}")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public Map<String, Object> pageRank(@PathVariable UUID siteId, @PathVariable String kind,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(defaultValue = "20") int limit) {
        List<AnalyticsReportRepository.PageRankRow> rows =
            "exit".equalsIgnoreCase(kind)
                ? reportRepo.exitPages(siteId, from, to, limit)
                : reportRepo.entryPages(siteId, from, to, limit);
        return Map.of(
            "bounceRate", round4(reportRepo.bounceRate(siteId, from, to)),
            "rows", rows.stream().map(r -> Map.<String, Object>of(
                "path", String.valueOf(r.path()), "sessionCount", r.sessionCount())).toList());
    }

    /** 媒体资产排行（FR-STAT-009）。 */
    @GetMapping("/assets/rank")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> assetRank(@PathVariable UUID siteId,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @RequestParam(defaultValue = "20") int limit) {
        return reportRepo.assetRank(siteId, from, to, limit).stream()
            .map(r -> Map.<String, Object>of(
                "mediaAssetId", String.valueOf(r.mediaAssetId()),
                "playCount", r.playCount(), "playSessionCount", r.playSessionCount(),
                "watchedMsTotal", r.watchedMsTotal(), "trafficBytesTotal", r.trafficBytesTotal()))
            .toList();
    }

    /** 区域分布（FR-STAT-005）：按省份（可选城市）聚合 PV/UV。 */
    @GetMapping("/regions")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> regions(@PathVariable UUID siteId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "50") int limit) {
        return reportRepo.regionReport(siteId, from, to, limit).stream()
            .map(r -> Map.<String, Object>of(
                "province", String.valueOf(r.province()),
                "city", String.valueOf(r.city()),
                "pv", r.pv(), "uv", r.uv()))
            .toList();
    }

    /** 最近访问记录（FR-STAT-065）：无 PII，倒序（保留旧口径：近 24 小时）。 */
    @Deprecated
    @GetMapping("/recent-visits")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> recentVisits(@PathVariable UUID siteId,
                                                  @RequestParam(defaultValue = "100") int limit) {
        Instant since = Instant.now().minusSeconds(86400);
        return reportRepo.recentVisits(siteId, since, limit).stream()
            .map(r -> Map.<String, Object>of(
                "occurredAt", String.valueOf(r.occurredAt()),
                "ipMasked", String.valueOf(r.ipMasked()),
                "routePath", String.valueOf(r.routePath()),
                "title", String.valueOf(r.title()),
                "sourceType", String.valueOf(r.sourceType()),
                "os", String.valueOf(r.os()),
                "browser", String.valueOf(r.browser()),
                "isNewVisitor", r.isNewVisitor()))
            .toList();
    }

    /**
     * 访问记录筛选查询（FR-STAT-065 扩展）：日期范围（站点时区）必选，
     * 可选按 IP（匿名掩码）/标题/来源/客户端/访客类型筛选，分页返回。
     */
    @GetMapping("/visit-log")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public Map<String, Object> visitLog(@PathVariable UUID siteId,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(defaultValue = "title") String searchIn,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String visitorKind,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        ZoneId zone = siteZone(siteId);
        var result = reportRepo.visitLog(siteId, from, to, zone, searchIn, q, visitorKind, page, size);
        List<Map<String, Object>> rows = result.rows().stream()
            .map(r -> Map.<String, Object>of(
                "occurredAt", String.valueOf(r.occurredAt()),
                "ipMasked", String.valueOf(r.ipMasked()),
                "routePath", String.valueOf(r.routePath()),
                "title", String.valueOf(r.title()),
                "sourceType", String.valueOf(r.sourceType()),
                "os", String.valueOf(r.os()),
                "browser", String.valueOf(r.browser()),
                "isNewVisitor", r.isNewVisitor()))
            .toList();
        return Map.of("total", result.total(), "rows", rows);
    }

    // ---------- 发布统计（FR-STAT-070~074） ----------

    /** 按用户工作量（FR-STAT-071/074）。 */
    @GetMapping("/publish/by-user")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats-admin')")
    public List<Map<String, Object>> publishByUser(@PathVariable UUID siteId,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ZoneId zone = siteZone(siteId);
        Instant fromTs = from.atStartOfDay(zone).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(zone).toInstant();
        return publishRepo.byUser(siteId, fromTs, toTs, null).stream()
            .map(r -> Map.<String, Object>of(
                "actor", String.valueOf(r.actor()),
                "drafts", r.drafts(), "submitted", r.submitted(), "approved", r.approved(),
                "rejected", r.rejected(), "published", r.published(),
                "withdrawn", r.withdrawn(), "archived", r.archived(),
                "avgReviewMinutes", r.avgReviewMinutes() == null ? "" : r.avgReviewMinutes()))
            .toList();
    }

    /** 按部门工作量（FR-STAT-072）：无部门归属计入「未分配」。 */
    @GetMapping("/publish/by-department")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats-admin')")
    public List<Map<String, Object>> publishByDepartment(@PathVariable UUID siteId,
                                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ZoneId zone = siteZone(siteId);
        Instant fromTs = from.atStartOfDay(zone).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(zone).toInstant();
        return publishRepo.byDepartment(siteId, fromTs, toTs).stream()
            .map(r -> Map.<String, Object>of(
                "deptName", String.valueOf(r.deptName()), "members", r.members(),
                "drafts", r.drafts(), "submitted", r.submitted(), "approved", r.approved(),
                "rejected", r.rejected(), "published", r.published(),
                "withdrawn", r.withdrawn(), "archived", r.archived()))
            .toList();
    }

    /** 按栏目工作量（FR-STAT-073）：内容当前状态按栏目计数。 */
    @GetMapping("/publish/by-folder")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> publishByFolder(@PathVariable UUID siteId) {
        return publishRepo.byFolder(siteId).stream()
            .map(r -> Map.<String, Object>of(
                "folderId", String.valueOf(r.folderId()),
                "folderTitle", String.valueOf(r.folderTitle()),
                "total", r.total(), "drafts", r.drafts(), "inReview", r.inReview(),
                "approved", r.approved(), "published", r.published(),
                "withdrawn", r.withdrawn(), "archived", r.archived()))
            .toList();
    }

    /** 工作量趋势（FR-STAT-074）：每日环节计数曲线。 */
    @GetMapping("/publish/trend")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<Map<String, Object>> publishTrend(@PathVariable UUID siteId,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ZoneId zone = siteZone(siteId);
        Instant fromTs = from.atStartOfDay(zone).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(zone).toInstant();
        return publishRepo.dailyTrend(siteId, fromTs, toTs).stream()
            .map(r -> Map.<String, Object>of(
                "statDate", r.statDate() == null ? "" : r.statDate().toString(),
                "submits", r.submits(), "approvals", r.approvals(),
                "rejections", r.rejections(), "publishes", r.publishes()))
            .toList();
    }

    public record RealtimeSummary(int onlineCount, int watchingCount, String view) {}

    /** 实时在线（FR-STAT-066/106）：默认全局跨节点去重；传 serverNode 返回节点视角。 */
    @GetMapping("/realtime/summary")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public RealtimeSummary realtimeSummary(@PathVariable UUID siteId,
                                           @RequestParam(required = false) String serverNode) {
        var since = Instant.now().minusSeconds(300);
        var snapshot = serverNode == null || serverNode.isBlank()
            ? eventStore.onlineSnapshot(siteId, since, null, true)
            : eventStore.onlineSnapshot(siteId, since, serverNode, false);
        return new RealtimeSummary(snapshot.onlineCount(), snapshot.watchingCount(),
            serverNode == null || serverNode.isBlank() ? "GLOBAL" : "NODE:" + serverNode);
    }

    public record MinutePoint(String minute, int onlineCount, int watchingCount) {}

    /** 在线曲线（FR-STAT-068）：默认读 __ALL__ 全局行；传 serverNode 读节点行。 */
    @GetMapping("/realtime/trend")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats')")
    public List<MinutePoint> realtimeTrend(@PathVariable UUID siteId,
                                           @RequestParam(defaultValue = "60") int minutes,
                                           @RequestParam(required = false) String serverNode) {
        String node = serverNode == null || serverNode.isBlank() ? AnalyticsAggregateService.GLOBAL_NODE : serverNode;
        return trendRepo.minuteTrend(siteId, node, minutes).stream()
            .map(r -> new MinutePoint(r.minute().toString(), r.onlineCount(), r.watchingCount()))
            .toList();
    }

    /**
     * @param playState 播放状态推导结果（PLAYING / PAUSED / STOPPED）：
     *                  会话已收口（end_reason 非空）即 STOPPED，否则看最新事件的暂停位
     * @param endReason 会话结束原因（仅 STOPPED 时有值，如 completed / timeout）
     */
    public record WatchingRow(
        String visitorId, String memberAccount, String contentTitle, String routePath,
        String ipMasked, String regionProvince, String regionCity,
        String os, String browser, String deviceType,
        long playPositionMs, String lastActiveAt, String serverNode,
        String playState, String endReason, long trafficBytes, long watchedMs) {}

    /** 播放状态口径：会话收口 > 暂停位 > 播放中。 */
    public static String playStateOf(boolean paused, String endReason) {
        if (endReason != null && !endReason.isBlank()) return "STOPPED";
        return paused ? "PAUSED" : "PLAYING";
    }

    /**
     * 实时观看列表（FR-STAT-069，需 stats-admin，FR-STAT-094）：
     * 在线窗口内观看者明细，支持按内容标题/用户/IP 段/地区检索与采集节点过滤。
     */
    @GetMapping("/realtime/watching")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats-admin')")
    public Map<String, Object> realtimeWatching(@PathVariable UUID siteId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "title") String searchIn,
                                                @RequestParam(required = false) String serverNode,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        var windowStart = Instant.now().minusSeconds(300);
        var result = watchingRepo.watching(siteId, windowStart, serverNode, searchIn, q,
            Math.max(0, page), Math.min(100, Math.max(1, size)));
        List<WatchingRow> rows = result.rows().stream()
            .map(r -> new WatchingRow(r.visitorId(), r.memberAccount(), r.contentTitle(), r.routePath(),
                r.ipMasked(), r.regionProvince(), r.regionCity(),
                r.os(), r.browser(), r.deviceType(),
                r.playPositionMs(),
                r.lastActiveAt() == null ? null : r.lastActiveAt().toString(),
                r.serverNode(),
                playStateOf(r.paused(), r.endReason()), r.endReason(),
                r.sessionTrafficBytes(), r.sessionWatchedMs()))
            .toList();
        return Map.of("total", result.total(), "rows", rows);
    }

    /** 节点列表（FR-STAT-107）。 */
    @GetMapping("/nodes")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats-admin')")
    public List<Map<String, Object>> nodes(@PathVariable UUID siteId) {
        return nodeRepo.findAll().stream()
            .map(n -> Map.<String, Object>of(
                "serverNode", n.getServerNode(),
                "host", String.valueOf(n.getHost()),
                "version", String.valueOf(n.getVersion()),
                "status", n.getStatus(),
                "queueDepth", n.getQueueDepth(),
                "droppedCount", n.getDroppedCount(),
                "lastHeartbeatAt", String.valueOf(n.getLastHeartbeatAt()),
                "configVersion", String.valueOf(n.getConfigVersion()),
                "geoLibVersion", String.valueOf(n.getGeoLibVersion())))
            .toList();
    }

    /** 节点优雅下线指令（FR-STAT-107）：标记 DRAINING，节点停止接流并 flush 后转 OFFLINE。 */
    @PostMapping("/nodes/{serverNode}/drain")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'stats-admin')")
    public ResponseEntity<Void> drain(@PathVariable UUID siteId, @PathVariable String serverNode) {
        nodeRepo.findById(serverNode).ifPresent(n -> {
            n.setStatus("DRAINING");
            nodeRepo.save(n);
        });
        return ResponseEntity.accepted().build();
    }
}
