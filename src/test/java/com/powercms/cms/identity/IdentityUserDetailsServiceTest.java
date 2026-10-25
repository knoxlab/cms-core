package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityUserDetailsServiceTest {

    @Test
    void expiredPasswordIsMarkedCredentialsExpired() {
        UserRepository users = mock(UserRepository.class);
        UserRoleRepository roles = mock(UserRoleRepository.class);
        IdentityProperties properties = new IdentityProperties();
        properties.setPasswordExpireDays(30);
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("editor1");
        user.setPasswordHash("{bcrypt}x");
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setPasswordChangedAt(Instant.now().minus(40, ChronoUnit.DAYS));
        when(users.findByUsername("editor1")).thenReturn(Optional.of(user));
        when(roles.findByUserId(user.getId())).thenReturn(List.of());

        IdentityUserDetailsService service = new IdentityUserDetailsService(
            users, roles, mock(RoleRepository.class), properties, mock(OrganizationSettingsService.class));

        assertThat(service.loadUserByUsername("editor1").isCredentialsNonExpired()).isFalse();
    }

    @Test
    void emailIdentifierAllowsLoginWithEmail() {
        OrganizationSettingsService settings = mock(OrganizationSettingsService.class);
        UserRepository users = mock(UserRepository.class);
        UserRoleRepository userRoles = mock(UserRoleRepository.class);
        UUID org = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPasswordHash("{bcrypt}x");
        user.setStatus(UserEntity.STATUS_ACTIVE);
        when(settings.defaultOrganizationId()).thenReturn(org);
        when(settings.loginIdentifier(org)).thenReturn("EMAIL");
        when(users.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
        when(users.findByUsername("alice@example.com")).thenReturn(Optional.empty());
        when(userRoles.findByUserId(user.getId())).thenReturn(List.of());

        IdentityUserDetailsService service = new IdentityUserDetailsService(
            users, userRoles, mock(RoleRepository.class), new IdentityProperties(), settings);

        assertThat(service.loadUserByUsername("alice@example.com").getUsername()).isEqualTo("alice");
    }

    @Test
    void zeroExpireDaysNeverExpires() {
        UserEntity user = new UserEntity();
        user.setPasswordChangedAt(Instant.now().minus(400, ChronoUnit.DAYS));
        assertThat(IdentityUserDetailsService.credentialsExpired(user, 0)).isFalse();
    }
}
