package com.powercms.cms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.powercms.cms.audit.entity.AuditEventEntity;
import com.powercms.cms.audit.repo.AuditEventRepository;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

class AuditServiceTest {

  private AuditEventRepository repo;
  private AuditService service;

  @BeforeEach
  void setUp() {
    repo = org.mockito.Mockito.mock(AuditEventRepository.class);
    when(repo.save(any(AuditEventEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    service = new AuditService(repo);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Nested
  class SyncWrite {

    @Test
    void sevenArgOverloadPassesTraceIdAsNull() {
      UUID siteId = UUID.randomUUID();
      service.write("alice", "content.approve", "Content", "42", siteId, "OK", "approved");

      ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
      verify(repo, times(1)).save(captor.capture());
      AuditEventEntity saved = captor.getValue();
      assertThat(saved.getActor()).isEqualTo("alice");
      assertThat(saved.getAction()).isEqualTo("content.approve");
      assertThat(saved.getObjectType()).isEqualTo("Content");
      assertThat(saved.getObjectId()).isEqualTo("42");
      assertThat(saved.getSiteId()).isEqualTo(siteId);
      assertThat(saved.getResult()).isEqualTo("OK");
      assertThat(saved.getDetail()).isEqualTo("approved");
      assertThat(saved.getTraceId()).isNull();
    }

    @Test
    void eightArgOverloadCarriesTraceId() {
      UUID siteId = UUID.randomUUID();
      service.write("alice", "content.approve", "Content", "42", siteId, "OK", "trace-1", "ok");

      ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
      verify(repo, times(1)).save(captor.capture());
      assertThat(captor.getValue().getTraceId()).isEqualTo("trace-1");
    }
  }

  @Nested
  class AsyncListener {

    @Test
    void onAuditEventPersistsEntity() {
      UUID siteId = UUID.randomUUID();
      AuditService.AuditEvent event = new AuditService.AuditEvent(
          "bob", "site.create", "Site", "1", siteId, "OK", "trace-2", "ok");

      service.onAuditEvent(event);

      ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
      verify(repo, times(1)).save(captor.capture());
      AuditEventEntity saved = captor.getValue();
      assertThat(saved.getActor()).isEqualTo("bob");
      assertThat(saved.getAction()).isEqualTo("site.create");
      assertThat(saved.getTraceId()).isEqualTo("trace-2");
    }

    @Test
    void onAuditEventSwallowsRepositoryException() {
      doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
          .when(repo).save(any(AuditEventEntity.class));

      AuditService.AuditEvent event = new AuditService.AuditEvent(
          "bob", "site.create", "Site", "1", null, "OK", null, "ok");

      // 异步写入失败不能影响调用方事务（FR-AUDIT-001）
      assertThatNoException().isThrownBy(() -> service.onAuditEvent(event));
    }
  }

  @Nested
  class CurrentActorResolution {

    @Test
    void noSecurityContextYieldsSystem() {
      assertThat(AuditService.currentActor()).isEqualTo("system");
    }

    @Test
    void anonymousAuthenticationYieldsAuthName() {
      // UsernamePasswordAuthenticationToken 构造时未传 principal 视作匿名
      var auth = new UsernamePasswordAuthenticationToken("anonymous", "n/a",
          List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
      SecurityContextHolder.getContext().setAuthentication(auth);

      assertThat(AuditService.currentActor()).isEqualTo("anonymous");
    }

    @Test
    void typedPrincipalYieldsUserId() {
      UUID uid = UUID.randomUUID();
      UserDetails delegate = new User("alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
      AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(uid, delegate);
      var auth = new UsernamePasswordAuthenticationToken(principal, "n/a",
          List.of(new SimpleGrantedAuthority("ROLE_USER")));
      SecurityContextHolder.getContext().setAuthentication(auth);

      assertThat(AuditService.currentActor()).isEqualTo(uid.toString());
    }
  }
}
