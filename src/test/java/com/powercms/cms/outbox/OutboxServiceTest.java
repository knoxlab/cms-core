package com.powercms.cms.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.powercms.cms.outbox.entity.OutboxEventEntity;
import com.powercms.cms.outbox.repo.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OutboxServiceTest {

  private OutboxEventRepository repo;
  private OutboxService service;

  @BeforeEach
  void setUp() {
    repo = org.mockito.Mockito.mock(OutboxEventRepository.class);
    when(repo.save(any(OutboxEventEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    service = new OutboxService(repo);
  }

  @Test
  void writeDelegatesToRepository() {
    service.write("content.approved", "Content", "42", "{\"id\":42}");

    ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(repo, times(1)).save(captor.capture());
    OutboxEventEntity saved = captor.getValue();

    assertThat(saved.getEventType()).isEqualTo("content.approved");
    assertThat(saved.getAggregateType()).isEqualTo("Content");
    assertThat(saved.getAggregateId()).isEqualTo("42");
    assertThat(saved.getPayload()).isEqualTo("{\"id\":42}");
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getOccurredAt()).isNotNull();
    assertThat(saved.getAttempts()).isZero();
    assertThat(saved.getPublishedAt()).isNull();
  }

  @Test
  void writePropagatesRepositoryException() {
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
        .when(repo).save(any(OutboxEventEntity.class));

    assertThatThrownBy(() -> service.write("x", "A", "1", "{}"))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class)
        .hasMessage("db down");
  }
}
