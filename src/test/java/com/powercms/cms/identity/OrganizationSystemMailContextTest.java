package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.OrganizationSettingsEntity;
import com.powercms.cms.identity.repo.OrganizationRepository;
import com.powercms.cms.identity.repo.OrganizationSettingsRepository;
import com.powercms.cms.identity.repo.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DEF-CFG-03：组织设置的 {@code fromEmail} / {@code mailDomain} / {@code portalUrl}
 * 接入真实发信链路（修复前这三项没有任何读取方）。
 */
class OrganizationSystemMailContextTest {

    private final OrganizationSettingsRepository settingsRepo = mock(OrganizationSettingsRepository.class);
    private final OrganizationRepository orgRepo = mock(OrganizationRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final OrganizationSettingsService service =
        new OrganizationSettingsService(settingsRepo, orgRepo, userRepo);
    private final OrganizationSystemMailContext context = new OrganizationSystemMailContext(service);

    @Test
    void fromEmailWinsOverMailDomain() {
        givenSettings(settings -> {
            settings.setFromEmail("no-reply@custom.example.com");
            settings.setMailDomain("ignored.example.com");
        });

        assertThat(context.fromAddress()).contains("no-reply@custom.example.com");
    }

    @Test
    void mailDomainComposesFromAddressWhenFromEmailMissing() {
        givenSettings(settings -> settings.setMailDomain("acme.example.com"));

        assertThat(context.fromAddress()).contains("noreply@acme.example.com");
    }

    @Test
    void blankValuesAreTreatedAsUnconfigured() {
        givenSettings(settings -> {
            settings.setFromEmail("   ");
            settings.setMailDomain("");
            settings.setPortalUrl("  ");
        });

        assertThat(context.fromAddress()).isEmpty();
        assertThat(context.portalUrl()).isEmpty();
    }

    @Test
    void portalUrlIsTrimmed() {
        givenSettings(settings -> settings.setPortalUrl("  https://portal.example.com  "));

        assertThat(context.portalUrl()).contains("https://portal.example.com");
    }

    @Test
    void noOrganizationYieldsEmptyInsteadOfFailing() {
        when(orgRepo.findAllIds()).thenReturn(List.of());

        assertThat(context.fromAddress()).isEmpty();
        assertThat(context.portalUrl()).isEmpty();
    }

    private void givenSettings(java.util.function.Consumer<OrganizationSettingsEntity> customizer) {
        UUID org = UUID.randomUUID();
        OrganizationSettingsEntity settings = OrganizationSettingsEntity.defaults(org);
        customizer.accept(settings);
        when(orgRepo.findAllIds()).thenReturn(List.of(org));
        when(settingsRepo.findById(org)).thenReturn(Optional.of(settings));
    }
}
