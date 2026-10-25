package com.powercms.cms.stats;

import com.powercms.cms.stats.repo.AnalyticsTrendRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-STAT-013 验收：跨天范围 UV/IP 必须按去重基表 COUNT(DISTINCT) 精确计算，
 * 同一访客跨两天访问范围 UV = 1（而非日值相加 2）。
 */
@SpringBootTest
@Transactional
class AnalyticsCrossDayDedupIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AnalyticsTrendRepository trendRepo;

    /** 五库方言：not-exists 占位 FROM（达梦/MySQL from dual）与 uuid CAST 目标类型。 */
    @Autowired
    private AnalyticsDialect dialect;

    @Test
    void crossDayRangeUvIsPreciselyDeduplicated() {
        UUID siteId = UUID.randomUUID();
        LocalDate d1 = LocalDate.of(2026, 8, 20);
        LocalDate d2 = LocalDate.of(2026, 8, 21);

        insertDailyRow(siteId, d1, "v-1", "1.2.3.0");
        insertDailyRow(siteId, d2, "v-1", "1.2.3.0");
        // 第二天新访客
        insertDailyRow(siteId, d2, "v-2", "1.2.3.0");

        var total = trendRepo.rangeTotal(siteId, d1, d2);
        // 基表精确去重：UV = 2（v-1 跨两天算 1 + v-2），非日值相加的 3
        assertThat(total.uv()).isEqualTo(2);
        assertThat(total.ipCount()).isEqualTo(1);
        // 单日范围回归：UV = 日内值
        var day1 = trendRepo.rangeTotal(siteId, d1, d1);
        assertThat(day1.uv()).isEqualTo(1);
    }

    private void insertDailyRow(UUID siteId, LocalDate date, String visitorHash, String ipMasked) {
        // 日聚合行（PV 可加部分）；H2 2.x 无 ON CONFLICT，update-then-insert 幂等
        int updated = em.createNativeQuery("""
                update analytics_site_daily set pv = pv + 1, uv = 1, ip_count = 1, session_count = 1
                where site_id = :siteId and stat_date = :date
                """)
            .setParameter("siteId", siteId)
            .setParameter("date", java.sql.Date.valueOf(date))
            .executeUpdate();
        if (updated == 0) {
            em.createNativeQuery("""
                    insert into analytics_site_daily (site_id, stat_date, pv, uv, ip_count, session_count)
                    values (:siteId, :date, 1, 1, 1, 1)
                    """)
                .setParameter("siteId", siteId)
                .setParameter("date", java.sql.Date.valueOf(date))
                .executeUpdate();
        }
        // 去重基表行：insert-select not exists 幂等。
        // FROM 占位与 uuid CAST 走 AnalyticsDialect（原硬编码 from (values (1)) marker
        // 在达梦报 Syntax error，mvntest6 实测；dual/varchar2 分支见 notExistsFromClause/castUuid）。
        em.createNativeQuery(
                "insert into analytics_visitor_daily (site_id, stat_date, visitor_hash)\n"
              + "select distinct " + dialect.castUuid(":siteId") + ", cast(:date as date), :vh\n"
              + dialect.notExistsFromClause()
              + "where not exists (\n"
              + "    select 1 from analytics_visitor_daily v\n"
              + "    where v.site_id = " + dialect.castUuid(":siteId")
              + " and v.stat_date = cast(:date as date) and v.visitor_hash = :vh)\n")
            .setParameter("siteId", siteId)
            .setParameter("date", java.sql.Date.valueOf(date))
            .setParameter("vh", visitorHash)
            .executeUpdate();
        em.createNativeQuery(
                "insert into analytics_ip_daily (site_id, stat_date, ip_hash)\n"
              + "select distinct " + dialect.castUuid(":siteId") + ", cast(:date as date), :ih\n"
              + dialect.notExistsFromClause()
              + "where not exists (\n"
              + "    select 1 from analytics_ip_daily i\n"
              + "    where i.site_id = " + dialect.castUuid(":siteId")
              + " and i.stat_date = cast(:date as date) and i.ip_hash = :ih)\n")
            .setParameter("siteId", siteId)
            .setParameter("date", java.sql.Date.valueOf(date))
            .setParameter("ih", ipMasked)
            .executeUpdate();
        em.flush();
        em.clear();
    }
}
