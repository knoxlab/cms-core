package com.powercms.cms.site;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.purge.SitePurgeCatalog;
import com.powercms.cms.site.purge.SitePurgePlanner;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 归档站点删除（FR-SITE-LC-007）：
 *
 * <ul>
 *   <li><b>仅已归档站点可删</b>——未归档返回 {@link ErrorCodes#CONFLICT}；</li>
 *   <li><b>删除 = 级联清除该站点的全部站点级数据</b>：按 {@link SitePurgeCatalog} 的
 *       「卫星表 → 站点作用域表」顺序执行，内容类型、字段、内容、版本、模板、主题、媒体、
 *       发布、工作流、互动、知识库、AI 与统计行一并物理删除（单事务，可回滚）；</li>
 *   <li><b>审计保留</b>：{@code audit_event} 不随站点删除（FR-AUDIT-001），删除动作本身写审计；</li>
 *   <li><b>外部引用先提示、后阻断</b>：其他站点/用户仍引用本站（跨站主题共享、主题继承、
 *       外部发布引用主题版本、用户主站点等）时，{@link #blockers} 返回明细，
 *       预览接口给前端展示，删除接口抛 {@link ErrorCodes#SITE_DELETE_BLOCKED}（409）。</li>
 * </ul>
 */
@Service
@Transactional
public class SiteDeletionService {

    private static final Logger log = LoggerFactory.getLogger(SiteDeletionService.class);

    private final SiteRepository siteRepo;
    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final SitePurgePlanner planner = new SitePurgePlanner(true);

    /**
     * 自引用继承列（父列可跨站指向本站行——这类边不在 {@link SitePurgeCatalog.EDGES}，
     * 必须显式做跨站引用检查，否则删到一半才被外键挡住）。
     *
     * <p>每条为 {@code {表, 引用列, 「他站行」谓词, 「本站行」谓词}}，两个谓词都以 {@code ?}
     * 接收 siteId。带 site_id 列的表直接按 site_id 划界；<b>卫星表</b>（无 site_id，如
     * {@code field_definition}）必须按其作用域推导列表（content_type）划界——对卫星表套用
     * {@code site_id} 谓词会直接报「Column site_id not found」，让预览/删除对所有站点都 500
     * （SiteDeletionServiceTest 覆盖此语义）。表与列名全部来自本常量，并过
     * {@link SitePurgeCatalog#isSafeIdentifier} 白名单。</p>
     */
    private static final List<String[]> SELF_REF_CHECKS = List.of(
        new String[] {"site_theme", "parent_theme_id", "site_id <> ?", "site_id = ?"},
        new String[] {"content_type", "parent_type_id", "site_id <> ?", "site_id = ?"},
        new String[] {"content_type", "copied_from_type_id", "site_id <> ?", "site_id = ?"},
        new String[] {"category", "parent_id", "site_id <> ?", "site_id = ?"},
        new String[] {"site_folder", "parent_id", "site_id <> ?", "site_id = ?"},
        // 卫星表：字段作用域由所属内容模型推导（跨模型/跨站继承 = 他站行）
        new String[] {"field_definition", "parent_id",
            "content_type_id IN (SELECT id FROM content_type WHERE site_id <> ?)",
            "content_type_id IN (SELECT id FROM content_type WHERE site_id = ?)"});

    SiteDeletionService(SiteRepository siteRepo, JdbcTemplate jdbc, AuditService auditService) {
        this.siteRepo = siteRepo;
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    /** 阻断项（代码稳定、数量随数据变化；前端按 code 本地化展示，message 作兜底）。 */
    public record Blocker(String code, String message, long count) {}

    /** 删除预览：影响面计数 + 阻断项。{@code deletable=false} 时前端只允许查看、不允许确认。 */
    public record DeletionPreview(String siteId, String status, Map<String, Long> impact,
                                  List<Blocker> blockers, boolean deletable) {}

    /** 删除结果（审计与响应用）。 */
    public record DeletionResult(String siteId, Map<String, Long> impact) {}

    /** 影响面预览（按站点作用域表计数）。 */
    public DeletionPreview preview(UUID siteId) {
        SiteEntity site = requireSite(siteId);
        Map<String, Long> impact = impact(siteId);
        List<Blocker> blockers = blockers(siteId);
        boolean deletable = SiteEntity.STATUS_ARCHIVED.equals(site.getStatus()) && blockers.isEmpty();
        return new DeletionPreview(siteId.toString(), site.getStatus(), impact, blockers, deletable);
    }

    /**
     * 物理删除站点及其全部站点级数据。
     *
     * @throws BusinessException SITE_DELETE_BLOCKED（仍有外部引用）或 CONFLICT（未归档）
     */
    public DeletionResult delete(UUID siteId) {
        SiteEntity site = requireSite(siteId);
        if (!SiteEntity.STATUS_ARCHIVED.equals(site.getStatus())) {
            throw BusinessException.keyed(ErrorCodes.CONFLICT, "站点未归档，仅已归档站点可删除",
                "error.site.delete.not.archived");
        }
        List<Blocker> blockers = blockers(siteId);
        if (!blockers.isEmpty()) {
            String joined = String.join("；", blockers.stream().map(Blocker::message).toList());
            log.warn("站点删除被阻止 siteId={} blockers={}", siteId, joined);
            throw BusinessException.keyed(ErrorCodes.SITE_DELETE_BLOCKED,
                "站点仍被外部引用，无法删除：" + joined, "error.site.delete.blocked");
        }

        Map<String, Long> impact = impact(siteId);
        SitePurgePlanner.SitePurgePlan plan = planner.plan();
        // 1) 解环置空（互引列先断开）
        for (String sql : plan.breakStatements()) {
            jdbc.update(sql, siteId);
        }
        // 2) 按拓扑序清表（卫星表 → 站点作用域表）
        for (String sql : plan.deleteStatements()) {
            jdbc.update(sql, siteId);
        }
        // 3) 最后删 site 行（此时已无任何指向它的引用）
        siteRepo.deleteById(siteId);

        String detail = new StringBuilder("站点已物理删除（含全部站点级数据，审计保留）：")
            .append(impact.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("无业务数据"))
            .append("；blockers=0").toString();
        auditService.write(AuditService.currentActor(), "SITE_DELETE", "site",
            siteId.toString(), siteId, "SUCCESS", detail);
        log.info("站点已删除 siteId={} impact={}", siteId, impact);
        return new DeletionResult(siteId.toString(), impact);
    }

    /** 站点行必须存在。 */
    SiteEntity requireSite(UUID siteId) {
        return siteRepo.findById(siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在"));
    }

    /** 确认弹窗影响面（只统计 {@link SitePurgeCatalog#IMPACT_TABLES}，全部有 site_id 列）。 */
    Map<String, Long> impact(UUID siteId) {
        Map<String, Long> impact = new LinkedHashMap<>();
        for (String table : SitePurgeCatalog.IMPACT_TABLES) {
            Long n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE site_id = ?", Long.class, siteId);
            impact.put(table, n != null ? n : 0L);
        }
        return impact;
    }

    /**
     * 外部引用检查——任何一条命中都必须阻断删除（否则外键会在删到一半时报错，
     * 或者静默破坏其他站点的数据）：
     * <ol>
     *   <li>用户以本站为主站点（{@code sys_user.home_site_id}）；</li>
     *   <li>其他站点消费本站主题（{@code site_theme_share}）、继承本站主题/内容模型/栏目等；</li>
     *   <li>其他站点的发布引用本站主题版本（{@code release_theme_version}）；</li>
     *   <li>目录内任意「其他站点 → 本站」引用边（{@link SitePurgeCatalog#EDGES}）。</li>
     * </ol>
     */
    List<Blocker> blockers(UUID siteId) {
        List<Blocker> out = new ArrayList<>();

        Long home = count("SELECT count(*) FROM sys_user WHERE home_site_id = ?", siteId);
        if (home > 0) {
            out.add(new Blocker("USER_HOME_SITE", "被 " + home + " 个用户设为主站点，请先改绑或清空其主站点", home));
        }

        Long share = count("SELECT count(*) FROM site_theme_share"
            + " WHERE theme_id IN (SELECT id FROM site_theme WHERE site_id = ?) AND consumer_site_id <> ?", siteId, siteId);
        if (share > 0) {
            out.add(new Blocker("THEME_SHARED", "主题被其他站点共享消费（site_theme_share × " + share + "），请先解除共享", share));
        }

        for (String[] check : SELF_REF_CHECKS) {
            String table = check[0];
            String column = check[1];
            String otherSites = check[2];
            String ownSite = check[3];
            // 标识符白名单（全部来自常量，防目录被误改后拼出危险语句）
            if (!SitePurgeCatalog.isSafeIdentifier(table)
                || !SitePurgeCatalog.isSafeIdentifier(column)) {
                throw new IllegalStateException("非法自引用检查标识符: " + table + "." + column);
            }
            Long n = count("SELECT count(*) FROM " + table + " WHERE " + otherSites
                + " AND " + column + " IN (SELECT id FROM " + table + " WHERE " + ownSite + ")",
                siteId, siteId);
            if (n > 0) {
                out.add(new Blocker("CROSS_SITE_INHERIT",
                    "其他站点的「" + table + "」通过 " + column + " 引用本站（" + n + " 行），请先解除继承", n));
            }
        }

        Long foreignRelease = count("SELECT count(*) FROM release_theme_version"
            + " WHERE release_id NOT IN (SELECT id FROM release WHERE site_id = ?)"
            + " AND (owner_site_id = ? OR theme_id IN (SELECT id FROM site_theme WHERE site_id = ?))",
            siteId, siteId, siteId);
        if (foreignRelease > 0) {
            out.add(new Blocker("FOREIGN_RELEASE_THEME_REF",
                "其他站点的发布引用本站主题版本（release_theme_version × " + foreignRelease + "）", foreignRelease));
        }

        // 目录内全部「站点作用域子表 → 父表」边的跨站引用
        for (SitePurgeCatalog.Edge edge : SitePurgeCatalog.EDGES) {
            boolean childScoped = SitePurgeCatalog.SITE_TABLES.contains(edge.child());
            boolean parentScoped = SitePurgeCatalog.SITE_TABLES.contains(edge.parent());
            if (!childScoped || !parentScoped || edge.child().equals(edge.parent())) continue;
            Long n = count("SELECT count(*) FROM " + edge.child()
                + " WHERE site_id <> ? AND " + edge.column()
                + " IN (SELECT id FROM " + edge.parent() + " WHERE site_id = ?)", siteId, siteId);
            if (n > 0) {
                out.add(new Blocker("CROSS_SITE_REFERENCE",
                    "其他站点的「" + edge.child() + "」引用本站（" + edge.column() + " × " + n + "）", n));
            }
        }
        return out;
    }

    private Long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n != null ? n : 0L;
    }
}
