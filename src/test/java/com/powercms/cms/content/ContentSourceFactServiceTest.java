package com.powercms.cms.content;

import com.powercms.cms.content.entity.ContentSourceFactEntity;
import com.powercms.cms.content.repo.ContentSourceFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FR-AI-SRC-004 统一来源事实：元数据 upsert 与引用裁决（审批状态/有效期/来源可信等级）。
 */
@ExtendWith(MockitoExtension.class)
class ContentSourceFactServiceTest {

    @Mock private ContentSourceFactRepository repo;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);
    private final UUID siteId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private ContentSourceFactService service;

    @BeforeEach
    void setUp() {
        service = new ContentSourceFactService(repo, clock);
    }

    private ContentSourceFactEntity fact(String approval, Instant from, Instant until, int trust) {
        ContentSourceFactEntity f = new ContentSourceFactEntity();
        f.setContentItemId(itemId);
        f.setSiteId(siteId);
        f.setSourceSystem("PIM:erp");
        f.setApprovalStatus(approval);
        f.setValidFrom(from);
        f.setValidUntil(until);
        f.setTrustLevel(trust);
        return f;
    }

    @Test
    void upsertCreatesThenUpdatesSameRow() {
        when(repo.findById(itemId)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentSourceFactEntity created = service.upsert(itemId, siteId, "PIM:erp", "SKU-1",
            "v1", ContentSourceFactEntity.APPROVAL_APPROVED, null, null, 2, "© 2026", "cur-1");
        assertThat(created.getContentItemId()).isEqualTo(itemId);
        assertThat(created.getSourceObjectId()).isEqualTo("SKU-1");
        assertThat(created.getSyncedAt()).isEqualTo(clock.instant());

        when(repo.findById(itemId)).thenReturn(Optional.of(created));
        ContentSourceFactEntity updated = service.upsert(itemId, siteId, "PIM:erp", "SKU-1",
            "v2", ContentSourceFactEntity.APPROVAL_PENDING, null, null, 1, "© 2026", "cur-2");
        assertThat(updated.getSourceVersion()).isEqualTo("v2");
        assertThat(updated.getApprovalStatus()).isEqualTo(ContentSourceFactEntity.APPROVAL_PENDING);
        assertThat(updated.getSyncCursor()).isEqualTo("cur-2");
    }

    @Test
    void noFactMeansNoExtraRestriction() {
        assertThat(service.rejectionReason(null, 3)).isNull();
    }

    @Test
    void pendingOrRejectedApprovalRejected() {
        assertThat(service.rejectionReason(
            fact(ContentSourceFactEntity.APPROVAL_PENDING, null, null, 3), 0))
            .isEqualTo("source_not_approved");
        assertThat(service.rejectionReason(
            fact(ContentSourceFactEntity.APPROVAL_REJECTED, null, null, 3), 0))
            .isEqualTo("source_not_approved");
    }

    @Test
    void validityWindowEnforced() {
        assertThat(service.rejectionReason(fact(ContentSourceFactEntity.APPROVAL_APPROVED,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"), 3), 0))
            .isEqualTo("source_expired");
        assertThat(service.rejectionReason(fact(ContentSourceFactEntity.APPROVAL_APPROVED,
            Instant.parse("2027-01-01T00:00:00Z"), null, 3), 0))
            .isEqualTo("source_not_yet_valid");
    }

    @Test
    void trustedWithinWindowAllowed() {
        assertThat(service.rejectionReason(fact(ContentSourceFactEntity.APPROVAL_APPROVED,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"), 3), 2))
            .isNull();
    }

    @Test
    void sourceTrustBelowMinimumRejected() {
        assertThat(service.rejectionReason(fact(ContentSourceFactEntity.APPROVAL_APPROVED,
            null, null, 1), 2)).isEqualTo("trust_below_min");
    }

    @Test
    void factsOfMapsByContentItem() {
        when(repo.findByContentItemIdIn(List.of(itemId))).thenReturn(List.of(
            fact(ContentSourceFactEntity.APPROVAL_APPROVED, null, null, 2)));
        assertThat(service.factsOf(List.of(itemId))).containsKey(itemId);
        assertThat(service.factsOf(List.of())).isEmpty();
    }
}
