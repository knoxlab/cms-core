package com.powercms.cms.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.powercms.cms.audit.entity.AuditEventEntity;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventEntityTest {

  @Test
  void factoryPopulatesAllFields() {
    Instant before = Instant.now();
    UUID siteId = UUID.randomUUID();
    AuditEventEntity e = AuditEventEntity.create(
        "alice", "content.approve", "Content", "42", siteId,
        "OK", "trace-1", "{\"before\":\"draft\",\"after\":\"approved\"}");
    Instant after = Instant.now();

    assertThat(e.getId()).isNotNull();
    assertThat(e.getActor()).isEqualTo("alice");
    assertThat(e.getAction()).isEqualTo("content.approve");
    assertThat(e.getObjectType()).isEqualTo("Content");
    assertThat(e.getObjectId()).isEqualTo("42");
    assertThat(e.getSiteId()).isEqualTo(siteId);
    assertThat(e.getResult()).isEqualTo("OK");
    assertThat(e.getTraceId()).isEqualTo("trace-1");
    assertThat(e.getDetail()).isEqualTo("{\"before\":\"draft\",\"after\":\"approved\"}");
    assertThat(e.getOccurredAt()).isBetween(before, after);
  }

  @Test
  void factoryAllowsNullableOptionals() {
    AuditEventEntity e = AuditEventEntity.create(
        "system", "noop", "Content", null, null, "OK", null, null);

    assertThat(e.getObjectId()).isNull();
    assertThat(e.getSiteId()).isNull();
    assertThat(e.getTraceId()).isNull();
    assertThat(e.getDetail()).isNull();
  }

  @Test
  void factoryGeneratesUniqueIds() {
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      ids.add(AuditEventEntity.create("actor", "act", "T", "1", null, "OK", null, null).getId());
    }
    assertThat(ids).hasSize(1000);
  }
}
