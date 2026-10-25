package com.powercms.cms.audit;

import com.powercms.cms.audit.entity.AuditEventEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessDenialQueryTest {

    @Test
    void blankActionReturnsOnlyAccessDenials() {
        AccessDenialQuery query = new AccessDenialQuery();
        List<AuditEventEntity> events = List.of(
            event("PAGE_ACCESS_DENIED"),
            event("PERMISSION_ACL"),
            event("MEDIA_DOWNLOAD_DENIED"));

        AccessDenialQuery.AuditPage page = query.query(ignored -> new PageImpl<>(events), null, 0, 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(AccessDenialQuery.Item::action)
            .containsExactly("PAGE_ACCESS_DENIED", "MEDIA_DOWNLOAD_DENIED");
    }

    @Test
    void actionFilterKeepsOneKind() {
        AccessDenialQuery query = new AccessDenialQuery();
        List<AuditEventEntity> events = List.of(
            event("MEDIA_PLAY_DENIED"),
            event("MEDIA_DOWNLOAD_DENIED"));

        AccessDenialQuery.AuditPage page = query.query(ignored -> new PageImpl<>(events), "MEDIA_PLAY_DENIED", 0, 20);

        assertThat(page.items()).extracting(AccessDenialQuery.Item::action).containsExactly("MEDIA_PLAY_DENIED");
    }

    @Test
    void unknownActionReturnsEmpty() {
        AccessDenialQuery query = new AccessDenialQuery();
        AccessDenialQuery.AuditPage page = query.query(
            ignored -> Page.empty(), "TOKEN_CREATE", 0, 20);
        assertThat(page.total()).isZero();
    }

    private static AuditEventEntity event(String action) {
        return AuditEventEntity.create("anonymous", action, "PAGE", "id", UUID.randomUUID(), "DENIED", null, null);
    }
}
