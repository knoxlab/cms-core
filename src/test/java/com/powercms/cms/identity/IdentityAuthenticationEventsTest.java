package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityAuthenticationEventsTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private IdentityAuthenticationEvents events;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        IdentityProperties properties = new IdentityProperties();
        properties.setMaxFailedLogins(5);
        properties.setFailureDelayMs(0);
        events = new IdentityAuthenticationEvents(users, properties, audit);
        user = new UserEntity();
        user.setId(java.util.UUID.randomUUID());
        user.setUsername("editor1");
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setFailedLogins(4);
        when(users.findByUsername("editor1")).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);
    }

    @Test
    void fifthFailureLocksAccount() {
        var auth = UsernamePasswordAuthenticationToken.unauthenticated("editor1", "bad");
        events.onFailure(new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad")));
        assertThat(user.getFailedLogins()).isEqualTo(5);
        assertThat(user.getStatus()).isEqualTo(UserEntity.STATUS_LOCKED);
        verify(users).save(user);
    }

    @Test
    void successClearsFailedLogins() {
        user.setFailedLogins(2);
        events.onSuccess(new AuthenticationSuccessEvent(new TestingAuthenticationToken("editor1", "x")));
        assertThat(user.getFailedLogins()).isZero();
    }
}
