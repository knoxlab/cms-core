package com.powercms.cms.outbox.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxEventEntityTest {

  @Test
  void factoryPopulatesAllFields() {
    Instant before = Instant.now();
    OutboxEventEntity e = OutboxEventEntity.create(
        "content.approved", "Content", "42", "{\"id\":42}");
    Instant after = Instant.now();

    assertThat(e.getId()).isNotNull();
    assertThat(e.getEventType()).isEqualTo("content.approved");
    assertThat(e.getAggregateType()).isEqualTo("Content");
    assertThat(e.getAggregateId()).isEqualTo("42");
    assertThat(e.getPayload()).isEqualTo("{\"id\":42}");
    assertThat(e.getOccurredAt()).isBetween(before, after);
    assertThat(e.getAttempts()).isZero();
    assertThat(e.getPublishedAt()).isNull();
    assertThat(e.getLastError()).isNull();
  }

  @Test
  void factoryGeneratesUniqueIds() {
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      ids.add(OutboxEventEntity.create("t", "A", String.valueOf(i), "{}").getId());
    }
    assertThat(ids).hasSize(1000);
  }

  @Test
  void publishedAtSetterStoresValue() {
    OutboxEventEntity e = OutboxEventEntity.create("t", "A", "1", "{}");
    assertThat(e.getPublishedAt()).isNull();

    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    e.setPublishedAt(t);
    assertThat(e.getPublishedAt()).isEqualTo(t);
  }

  @Test
  void attemptsAndLastErrorAreMutable() {
    OutboxEventEntity e = OutboxEventEntity.create("t", "A", "1", "{}");
    e.setAttempts(3);
    e.setLastError("upstream 503");
    assertThat(e.getAttempts()).isEqualTo(3);
    assertThat(e.getLastError()).isEqualTo("upstream 503");
  }
}
