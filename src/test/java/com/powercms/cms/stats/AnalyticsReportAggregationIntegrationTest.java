package com.powercms.cms.stats;

import com.powercms.cms.stats.repo.AnalyticsReportRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度日聚合 + 报表查询闭环（FR-STAT-012/014/020/030/031/040/050/060/065）：
 * 事件明细 → aggregateDay → 报表仓库 → 数值一致（NFR-STAT-003 幂等可重跑）。
 * aggregateDay 为 REQUIRES_NEW 事务，测试中事件插入先经独立事务提交（TX_1），断言走只读事务。
 */
@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "powercms.analytics.node-registration-enabled=false"
})
class AnalyticsReportAggregationIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AnalyticsAggregateService aggregateService;

    @Autowired
    private AnalyticsReportRepository reportRepo;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private static final LocalDate DAY = LocalDate.of(2026, 8, 20);

    /**
     * H2 TIMESTAMP 无时区语义，绑定随 JVM 默认时区偏移；测试统一取 JVM 默认时区的
     * 墙钟时刻，保证 extract(hour) 与预期一致（与聚合的 statDate 时区口径相同）。
     */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    private void insertEvent(UUID siteId, String type, Instant occurredAt, String visitor,
                             String path, UUID contentId, UUID typeId, String source,
                             String os, String browser) {
        em.createNativeQuery("""
                insert into analytics_event (id, site_id, event_type, occurred_at, session_id, visitor_id,
                    route_path, content_id, content_type_id, source_type, os, browser, is_bot, is_spa_nav)
                values (:id, :siteId, :type, :at, :session, :visitor,
                    :path, :contentId, :typeId, :source, :os, :browser, :isBot, :isSpaNav)
                """)
            .setParameter("id", UUID.randomUUID())
            .setParameter("siteId", siteId)
            .setParameter("type", type)
            .setParameter("at", Timestamp.from(occurredAt))
            .setParameter("session", "s-" + visitor)
            .setParameter("visitor", visitor)
            .setParameter("path", path)
            .setParameter("contentId", contentId)
            .setParameter("typeId", typeId)
            .setParameter("source", source)
            .setParameter("os", os)
            .setParameter("browser", browser)
            // bit/boolean 列以参数绑定（达梦无 false 字面量：Data type mismatch，
            // mvntest6 实测；setBoolean 在五库均合法）
            .setParameter("isBot", false)
            .setParameter("isSpaNav", false)
            .executeUpdate();
    }

    /** 事件插入走独立已提交事务（REQUIRES_NEW 的 aggregateDay 才能看到）。 */
    private UUID commitEvents(java.util.function.Consumer<UUID> seeder) {
        UUID siteId = UUID.randomUUID();
        org.springframework.transaction.support.TransactionTemplate tpl =
            new org.springframework.transaction.support.TransactionTemplate(txManager);
        tpl.executeWithoutResult(status -> seeder.accept(siteId));
        return siteId;
    }

    @Test
    void aggregateDayProducesQueryableDimensionReports() {
        UUID contentId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();

        UUID siteId = commitEvents(sid -> {
            // 访客 v1：两 PV（10 时/11 时，同会话）+ 搜索引擎来源；v2：单 PV（14 时，直接输入）
            insertEvent(sid, "PAGEVIEW", at(10), "v1", "/news/a", contentId, typeId, "SEARCH_ENGINE", "Windows", "Chrome");
            insertEvent(sid, "PAGEVIEW", at(11), "v1", "/news/b", contentId, typeId, "SEARCH_ENGINE", "Windows", "Chrome");
            insertEvent(sid, "PAGEVIEW", at(14), "v2", "/", contentId, typeId, "DIRECT", "Mac", "Safari");
            insertEvent(sid, "VIDEO_PLAY", at(15), "v1", "/video/x", contentId, typeId, "DIRECT", "Windows", "Chrome");
        });

        aggregateService.aggregateDay(siteId, DAY, ZONE);
        // 幂等重跑不产生重复（NFR-STAT-003）
        aggregateService.aggregateDay(siteId, DAY, ZONE);

        // 站点日汇总：PV=3, UV=2, 会话=2
        var total = em.createNativeQuery("""
                select pv, uv, session_count from analytics_site_daily
                where site_id = :siteId and stat_date = :date
                """)
            .setParameter("siteId", siteId)
            .setParameter("date", java.sql.Date.valueOf(DAY))
            .getResultList();
        assertThat(total).hasSize(1);
        Object[] siteRow = (Object[]) total.get(0);
        assertThat(((Number) siteRow[0]).longValue()).isEqualTo(3);
        assertThat(((Number) siteRow[1]).longValue()).isEqualTo(2);
        assertThat(((Number) siteRow[2]).longValue()).isEqualTo(2);

        // 时段分布（FR-STAT-012）：10/11/14 时各有 PV
        var hourly = reportRepo.hourlyDistribution(siteId, DAY, DAY);
        assertThat(hourly).extracting("hour").contains(10, 11, 14);

        // 内容排行（FR-STAT-030）：PV 排行 1 行 3 PV；play 口径 1
        var rank = reportRepo.contentRank(siteId, DAY, DAY, null, "pv", false, 10);
        assertThat(rank).hasSize(1);
        assertThat(rank.get(0).pv()).isEqualTo(3);
        var playRank = reportRepo.contentRank(siteId, DAY, DAY, typeId, "play", false, 10);
        assertThat(playRank.get(0).playCount()).isEqualTo(1);

        // 内容类型统计（FR-STAT-031）
        var types = reportRepo.contentTypeReport(siteId, DAY, DAY);
        assertThat(types).hasSize(1);
        assertThat(types.get(0).pv()).isEqualTo(3);

        // 来源统计（FR-STAT-040）：SEARCH_ENGINE 2 PV / DIRECT 2 PV
        var sources = reportRepo.sourceReport(siteId, DAY, DAY);
        assertThat(sources).extracting("sourceType").contains("SEARCH_ENGINE", "DIRECT");

        // 客户端（FR-STAT-050）：os 维度 Windows 3 PV / Mac 1 PV
        var clients = reportRepo.clientReport(siteId, "os", DAY, DAY);
        assertThat(clients).extracting("dimensionValue").contains("Windows", "Mac");

        // 访问深度（FR-STAT-060）：v1 会话 2 页、v2 会话 1 页 → 均落 '1-2' 档（2 个会话）
        var depth = reportRepo.depthReport(siteId, DAY, DAY);
        assertThat(depth).extracting("bucket").containsExactly("1-2");
        assertThat(depth.get(0).count()).isEqualTo(2);

        // 最近访问（FR-STAT-065）
        var recent = reportRepo.recentVisits(siteId, at(0), 10);
        assertThat(recent).hasSize(3);
    }

    @Test
    void bounceRateMatchesSinglePageSessions() {
        UUID siteId = commitEvents(sid -> {
            // 3 个会话：s-a 单页（跳出）、s-b 两页、s-c 单页（跳出）→ 跳出率 2/3
            insertEvent(sid, "PAGEVIEW", at(10), "a", "/x", null, null, "DIRECT", "Windows", "Chrome");
            insertEvent(sid, "PAGEVIEW", at(10), "b", "/y", null, null, "DIRECT", "Windows", "Chrome");
            insertEvent(sid, "PAGEVIEW", at(11), "b", "/z", null, null, "DIRECT", "Windows", "Chrome");
            insertEvent(sid, "PAGEVIEW", at(12), "c", "/w", null, null, "DIRECT", "Windows", "Chrome");
        });

        double rate = reportRepo.bounceRate(siteId, DAY, DAY);
        assertThat(rate).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    /** 日内增量聚合（当日）：当日事件经 aggregateTodayForSite 即进入维度报表（T+0 可见）。 */
    @Test
    void aggregateTodayMakesIntradayDimensionReportsQueryable() {
        LocalDate today = LocalDate.now(ZONE);
        UUID contentId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();

        UUID siteId = commitEvents(sid ->
            insertEvent(sid, "PAGEVIEW", today.atTime(10, 0).atZone(ZONE).toInstant(),
                "t1", "/", contentId, typeId, "DIRECT", "iOS", "Safari"));

        aggregateService.aggregateTodayForSite(siteId, ZONE);

        // 与日任务同口径：客户端/来源/深度均可查（当日窗口 [今日0点, 明日0点)）
        var clients = reportRepo.clientReport(siteId, "os", today, today);
        assertThat(clients).extracting("dimensionValue").contains("iOS");
        var sources = reportRepo.sourceReport(siteId, today, today);
        assertThat(sources).extracting("sourceType").contains("DIRECT");
        var depth = reportRepo.depthReport(siteId, today, today);
        assertThat(depth).isNotEmpty();
    }

    /**
     * 循环路径（aggregateYesterday/aggregateToday → self.aggregateDay）：自调用绕过代理曾致
     * REQUIRES_NEW 不生效、executeUpdate 抛 TransactionRequiredException——凌晨日聚合任务
     * 整体失败、报表 T+1 仍为空。经自代理修复后，逐站点独立事务跑通并可查询。
     */
    @Test
    void aggregateYesterdayLoopRunsWithPerSiteTransaction() {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        UUID orgId = UUID.randomUUID();

        UUID siteId = commitEvents(sid -> {
            em.createNativeQuery("""
                    insert into organization (id, name, created_at, updated_at)
                    values (:id, '统计聚合循环测试组织', timestamp '2026-01-01 00:00:00', timestamp '2026-01-01 00:00:00')
                    """)
                .setParameter("id", orgId)
                .executeUpdate();
            em.createNativeQuery("""
                    insert into site (id, organization_id, site_key, name, status)
                    values (:sid, :orgId, 'agg-loop-site', '聚合循环测试站', 'ACTIVE')
                    """)
                .setParameter("sid", sid)
                .setParameter("orgId", orgId)
                .executeUpdate();
            insertEvent(sid, "PAGEVIEW", yesterday.atTime(9, 0).atZone(ZONE).toInstant(),
                "loop-v", "/", null, null, "DIRECT", "Windows", "Chrome");
        });

        // 经自代理的循环路径：不再抛 TransactionRequiredException
        aggregateService.aggregateYesterday(Instant.now());

        var clients = reportRepo.clientReport(siteId, "os", yesterday, yesterday);
        assertThat(clients).extracting("dimensionValue").contains("Windows");
    }
}
