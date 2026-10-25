package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.platform.i18n.I18nConfig;
import com.powercms.cms.platform.i18n.SupportedLocales;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLanguageServiceImplTest {

    @Mock UserRepository userRepo;
    @Mock OrganizationSettingsService settingsService;

    private UserLanguageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserLanguageServiceImpl(userRepo, settingsService, new I18nConfig().messageSource());
    }

    @Test
    void updateLanguageNormalizesAliasAndPersists() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        String saved = service.updateLanguage(userId, "en");

        assertThat(saved).isEqualTo("en-US");
        assertThat(user.getLanguage()).isEqualTo("en-US");
        verify(userRepo).save(user);
        assertThat(SupportedLocales.tag(service.localeOf(userId))).isEqualTo("en-US");
    }

    @Test
    void updateLanguageRejectsUnsupportedTag() {
        assertThatThrownBy(() -> service.updateLanguage(UUID.randomUUID(), "de-DE"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException be = (BusinessException) ex;
                assertThat(be.getCode()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
                assertThat(be.getMessage()).contains("de-DE");
            });
    }

    @Test
    void updateLanguageMissingUserIsNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepo.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateLanguage(userId, "ja-JP"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.NOT_FOUND);
    }
}
