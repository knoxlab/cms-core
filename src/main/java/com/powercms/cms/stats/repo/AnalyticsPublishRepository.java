package com.powercms.cms.stats.repo;

import com.powercms.cms.stats.AnalyticsDialect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 发布统计查询（FR-STAT-070~074）：工作量按用户/部门/栏目聚合，数据源为
 * workflow_transition_log（追加写，统计不改原始记录，FR-STAT-070）与内容状态机。
 * 时区口径与访问统计一致：传站点时区的日界 Instant。
 */
@Service
public class AnalyticsPublishRepository {

    @PersistenceContext
    private EntityManager em;

    private final AnalyticsDialect dialect;

    public AnalyticsPublishRepository(AnalyticsDialect dialect) {
        this.dialect = dialect;
    }

    public record UserWorkloadRow(String actor, long drafts, long submitted, long approved, long rejected,
                                  long published, long withdrawn, long archived, long reviewCount,
                                  Long avgReviewMinutes) {}

    /** 按用户工作量（FR-STAT-071）。审核时长 = 提交→批准配对的分钟均值。 */
    @SuppressWarnings("unchecked")
    public List<UserWorkloadRow> byUser(UUID siteId, Instant from, Instant to, String actorPrefix) {
        String avgReview = dialect.castBigint(
            "avg(case when t.action_key = 'approve' then "
                + dialect.epochMinutes("t.created_at", "s.submit_at") + " end)");
        List<Object[]> rows = em.createNativeQuery("""
                select t.actor,
                       count(case when t.action_key = 'save_draft' then 1 end),
                       count(case when t.action_key = 'submit_for_review' then 1 end),
                       count(case when t.action_key = 'approve' then 1 end),
                       count(case when t.action_key = 'reject' then 1 end),
                       count(case when t.action_key = 'publish' then 1 end),
                       count(case when t.action_key = 'withdraw' then 1 end),
                       count(case when t.action_key = 'archive' then 1 end),
                       count(case when t.action_key = 'submit_for_review' then 1 end),
                       """ + avgReview + """
                from workflow_transition_log t
                left join (
                    select sub.task_id, min(sub.created_at) submit_at
                    from workflow_transition_log sub
                    where sub.site_id = :siteId and sub.action_key = 'submit_for_review'
                    group by sub.task_id
                ) s on s.task_id = t.task_id
                where t.site_id = :siteId and t.created_at >= :from and t.created_at < :to
                group by t.actor
                order by 2 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .getResultList();
        return rows.stream()
            .filter(r -> actorPrefix == null || String.valueOf(r[0]).startsWith(actorPrefix))
            .map(r -> new UserWorkloadRow(
                (String) r[0], num(r[1]), num(r[2]), num(r[3]), num(r[4]),
                num(r[5]), num(r[6]), num(r[7]), num(r[8]),
                r[9] instanceof Number n ? n.longValue() : null))
            .toList();
    }

    public record DeptWorkloadRow(String deptName, long members, long drafts, long submitted, long approved,
                                  long rejected, long published, long withdrawn, long archived) {}

    /** 按部门工作量（FR-STAT-072）：按用户当前部门聚合（新周期按新部门，FR-STAT-072 口径）。 */
    @SuppressWarnings("unchecked")
    public List<DeptWorkloadRow> byDepartment(UUID siteId, Instant from, Instant to) {
        List<Object[]> rows = em.createNativeQuery("""
                select coalesce(o.name, '未分配'),
                       count(distinct t.actor),
                       count(case when t.action_key = 'save_draft' then 1 end),
                       count(case when t.action_key = 'submit_for_review' then 1 end),
                       count(case when t.action_key = 'approve' then 1 end),
                       count(case when t.action_key = 'reject' then 1 end),
                       count(case when t.action_key = 'publish' then 1 end),
                       count(case when t.action_key = 'withdraw' then 1 end),
                       count(case when t.action_key = 'archive' then 1 end)
                from workflow_transition_log t
                left join sys_user u on u.username = t.actor
                left join organization o on o.id = u.organization_id
                where t.site_id = :siteId and t.created_at >= :from and t.created_at < :to
                group by coalesce(o.name, '未分配')
                order by 3 desc
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .getResultList();
        return rows.stream().map(r -> new DeptWorkloadRow(
            (String) r[0], num(r[1]), num(r[2]), num(r[3]), num(r[4]),
            num(r[5]), num(r[6]), num(r[7]), num(r[8]))).toList();
    }

    public record FolderWorkloadRow(UUID folderId, String folderTitle, long total, long drafts,
                                    long inReview, long approved, long published, long withdrawn, long archived) {}

    /** 按栏目工作量（FR-STAT-073）：内容当前状态按栏目口径计数。 */
    @SuppressWarnings("unchecked")
    public List<FolderWorkloadRow> byFolder(UUID siteId) {
        List<Object[]> rows = em.createNativeQuery("""
                select i.folder_id, coalesce(f.title, '未归类'),
                       count(*),
                       count(case when i.status = 'DRAFT' then 1 end),
                       count(case when i.status = 'IN_REVIEW' then 1 end),
                       count(case when i.status = 'APPROVED' then 1 end),
                       count(case when i.status = 'PUBLISHED' then 1 end),
                       count(case when i.status = 'WITHDRAWN' then 1 end),
                       count(case when i.status = 'ARCHIVED' then 1 end)
                from content_item i
                left join site_folder f on f.id = i.folder_id
                where i.site_id = :siteId
                group by i.folder_id, coalesce(f.title, '未归类')
                order by 3 desc
                """)
            .setParameter("siteId", siteId)
            .getResultList();
        return rows.stream().map(r -> new FolderWorkloadRow(
            AnalyticsReportRepository.asUuid(r[0]), (String) r[1], num(r[2]), num(r[3]), num(r[4]),
            num(r[5]), num(r[6]), num(r[7]), num(r[8]))).toList();
    }

    public record DailyActionPoint(LocalDate statDate, long submits, long approvals, long rejections, long publishes) {}

    /** 工作量趋势（FR-STAT-074）：每日各环节计数。 */
    @SuppressWarnings("unchecked")
    public List<DailyActionPoint> dailyTrend(UUID siteId, Instant from, Instant to) {
        List<Object[]> rows = em.createNativeQuery("""
                select cast(created_at as date),
                       count(case when action_key = 'submit_for_review' then 1 end),
                       count(case when action_key = 'approve' then 1 end),
                       count(case when action_key = 'reject' then 1 end),
                       count(case when action_key = 'publish' then 1 end)
                from workflow_transition_log
                where site_id = :siteId and created_at >= :from and created_at < :to
                group by cast(created_at as date)
                order by 1
                """)
            .setParameter("siteId", siteId)
            .setParameter("from", Timestamp.from(from))
            .setParameter("to", Timestamp.from(to))
            .getResultList();
        return rows.stream().map(r -> new DailyActionPoint(
            r[0] instanceof java.sql.Date d ? d.toLocalDate() : null,
            num(r[1]), num(r[2]), num(r[3]), num(r[4]))).toList();
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}
