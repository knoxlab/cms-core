package com.powercms.cms.audit;

import com.powercms.cms.audit.entity.AuditEventEntity;
import com.powercms.cms.audit.repo.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵 I1 / 验收回归（2026-10-19）：审计保留清理必须在数据库上真实分批删除。
 * {@link AuditRetentionJobTest} 走 Mockito 桩、不执行 SQL，故另有本用例锁住落库行为。
 *
 * <p>锁定三件事：</p>
 * <ul>
 *   <li>取号按 {@code occurred_at ASC} 生效——每批取最旧的一批，分批可收敛；</li>
 *   <li>cutoff 过滤：未到期行在多轮批次后仍保留；</li>
 *   <li>按主键批量删除可在生产形态下执行（每批独立事务，方法无外层事务）。</li>
 * </ul>
 *
 * <p>历史：原实现为 native {@code DELETE ... IN (SELECT ... LIMIT :limit)}，
 * 在 MySQL 触发 1235（LIMIT 不允许出现在 IN 子查询）且 @Query 校验器不接受
 * LIMIT 绑定参数；现改为 JPQL 取号 + 分批删除，本用例随之验证新路径。</p>
 *
 * <p>基线取 2020 年：cutoff 早于任何真实审计行（2026），保证只有本用例插入的
 * 行会被判定为过期，不受共享 H2 上下文中其他用例残留数据影响。</p>
 */
@SpringBootTest
class AuditEventRetentionDeleteIntegrationTest {

    private static final Instant BASE = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant CUTOFF = BASE.plus(Duration.ofDays(10));

    @Autowired
    private AuditEventRepository repo;

    @Test
    void batchedDeleteRemovesOldestFirstAndHonorsCutoff() {
        UUID oldest = insertEvent(BASE.plus(Duration.ofDays(1)));
        UUID middle = insertEvent(BASE.plus(Duration.ofDays(2)));
        UUID newestExpired = insertEvent(BASE.plus(Duration.ofDays(3)));
        UUID fresh = insertEvent(BASE.plus(Duration.ofDays(300)));

        // 第一批 limit=1：取号只返回最旧一条（ORDER BY occurred_at ASC 分批语义生效）
        List<UUID> firstBatch = repo.findExpiredIds(CUTOFF, PageRequest.of(0, 1));
        assertThat(firstBatch).containsExactly(oldest);
        assertThat(repo.deleteByIdInBatch(firstBatch)).isEqualTo(1);
        assertThat(repo.findById(oldest)).isEmpty();
        assertThat(repo.findById(middle)).isPresent();
        assertThat(repo.findById(newestExpired)).isPresent();

        // 第二批：余下过期行删净，未到期行保留
        List<UUID> rest = repo.findExpiredIds(CUTOFF, PageRequest.of(0, 5000));
        assertThat(rest).containsExactlyInAnyOrder(middle, newestExpired);
        assertThat(repo.deleteByIdInBatch(rest)).isEqualTo(2);
        assertThat(repo.findById(middle)).isEmpty();
        assertThat(repo.findById(newestExpired)).isEmpty();
        assertThat(repo.findById(fresh)).isPresent();

        // 幂等：再次取号为空，无行可删
        assertThat(repo.findExpiredIds(CUTOFF, PageRequest.of(0, 5000))).isEmpty();
    }

    private UUID insertEvent(Instant occurredAt) {
        AuditEventEntity e = AuditEventEntity.create(
            "tester", "test.action", "test-object", null, null, "OK", null, null);
        ReflectionTestUtils.setField(e, "occurredAt", occurredAt);
        repo.save(e);
        return e.getId();
    }
}
