package com.powercms.cms.identity;

import com.powercms.cms.identity.dto.UpdateOrganizationSettingsRequest;
import com.powercms.cms.identity.entity.OrganizationSettingsEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.OrganizationRepository;
import com.powercms.cms.identity.repo.OrganizationSettingsRepository;
import com.powercms.cms.identity.repo.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P1-C：组织设置与 loginIdentifier（AC-IAM-019）。 */
class OrganizationSettingsServiceTest {

    private final OrganizationSettingsRepository settingsRepo = mock(OrganizationSettingsRepository.class);
    private final OrganizationRepository orgRepo = mock(OrganizationRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final OrganizationSettingsService service =
        new OrganizationSettingsService(settingsRepo, orgRepo, userRepo);

    @Test
    void defaultsToUsernameIdentifierWithoutSettingsRow() {
        UUID org = UUID.randomUUID();
        when(settingsRepo.findById(org)).thenReturn(Optional.empty());
        assertThat(service.loginIdentifier(org)).isEqualTo("USERNAME");
    }

    @Test
    void updatePersistsLoginIdentifier() {
        UUID org = UUID.randomUUID();
        OrganizationSettingsEntity settings = OrganizationSettingsEntity.defaults(org);
        when(settingsRepo.findById(org)).thenReturn(Optional.of(settings));
        when(orgRepo.existsById(org)).thenReturn(true);
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(org, new UpdateOrganizationSettingsRequest(
            "email", "https://portal.example.com", "example.com", "no-reply@example.com",
            "en", "UTC", null));

        assertThat(response.loginIdentifier()).isEqualTo("EMAIL");
        assertThat(response.portalUrl()).isEqualTo("https://portal.example.com");
        assertThat(service.loginIdentifier(org)).isEqualTo("EMAIL");
    }

    @Test
    void emailIdentifierResolvesByEmailOnly() {
        UUID org = UUID.randomUUID();
        OrganizationSettingsEntity settings = OrganizationSettingsEntity.defaults(org);
        settings.setLoginIdentifier("EMAIL");
        when(settingsRepo.findById(org)).thenReturn(Optional.of(settings));

        UserEntity byEmail = new UserEntity();
        byEmail.setId(UUID.randomUUID());
        byEmail.setUsername("alice");
        byEmail.setEmail("alice@example.com");
        when(userRepo.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(byEmail));

        // EMAIL 标识下按邮箱命中
        assertThat(service.resolveLoginUser(org, "alice@example.com")).isSameAs(byEmail);
        // 用户名在 EMAIL 模式下不可登录（AC-IAM-019）
        when(userRepo.findByEmailIgnoreCase("alice")).thenReturn(Optional.empty());
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(byEmail));
        assertThat(service.resolveLoginUser(org, "alice")).isNull();
    }

    @Test
    void usernameIdentifierResolvesByUsernameOnly() {
        UUID org = UUID.randomUUID();
        when(settingsRepo.findById(org)).thenReturn(Optional.empty()); // 默认 USERNAME

        UserEntity byUsername = new UserEntity();
        byUsername.setId(UUID.randomUUID());
        byUsername.setUsername("bob");
        byUsername.setEmail("bob@example.com");
        when(userRepo.findByUsername("bob")).thenReturn(Optional.of(byUsername));

        assertThat(service.resolveLoginUser(org, "bob")).isSameAs(byUsername);

        // 邮箱在 USERNAME 模式下不可登录（AC-IAM-019）
        when(userRepo.findByUsername("bob@example.com")).thenReturn(Optional.empty());
        when(userRepo.findByEmailIgnoreCase("bob@example.com")).thenReturn(Optional.of(byUsername));
        assertThat(service.resolveLoginUser(org, "bob@example.com")).isNull();
    }

    @Test
    void defaultOrganizationIdTakesFirst() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(orgRepo.findAllIds()).thenReturn(List.of(first, second));
        assertThat(service.defaultOrganizationId()).isEqualTo(first);
    }

    @Test
    void publicBrandingExposesLoginIdentifierButNotMailInfrastructure() {
        UUID org = UUID.randomUUID();
        OrganizationSettingsEntity settings = OrganizationSettingsEntity.defaults(org);
        settings.setLoginIdentifier("EMAIL");
        settings.setMailDomain("example.com");
        settings.setFromEmail("no-reply@example.com");
        settings.setPortalUrl("https://portal.example.com");
        settings.setDefaultLanguage("zh-CN");
        settings.setDefaultTimezone("Asia/Shanghai");
        settings.setBranding("{\"loginBackground\":\"#1e3a5f\"}");
        when(orgRepo.findAllIds()).thenReturn(List.of(org));
        when(settingsRepo.findById(org)).thenReturn(Optional.of(settings));

        var branding = service.publicBranding();

        // DEF-CFG-02：登录页要按模式渲染正确的输入框标签，故 loginIdentifier 必须公开。
        assertThat(branding.loginIdentifier()).isEqualTo("EMAIL");
        assertThat(branding.portalUrl()).isEqualTo("https://portal.example.com");
        assertThat(branding.branding()).isEqualTo("{\"loginBackground\":\"#1e3a5f\"}");
        // 发信基础设施信息不公开（DEF-CFG-03 接入发信链路后更需守住这条）。
        assertThat(branding.mailDomain()).isNull();
        assertThat(branding.fromEmail()).isNull();
    }

    @Test
    void publicBrandingFallsBackToUsernameWhenNoOrganization() {
        when(orgRepo.findAllIds()).thenReturn(List.of());

        var branding = service.publicBranding();

        assertThat(branding.loginIdentifier()).isNull();
        assertThat(branding.portalUrl()).isNull();
        assertThat(branding.branding()).isNull();
    }
}
