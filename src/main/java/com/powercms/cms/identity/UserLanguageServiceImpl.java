package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.platform.i18n.Messages;
import com.powercms.cms.platform.i18n.SupportedLocales;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语言偏好实现（FR-I18N-004）：sys_user.language 持久化 + 进程内缓存。
 * 缓存与用户行同事务失效，供每请求的 Locale 解析免查库。
 */
@Service
public class UserLanguageServiceImpl implements UserLanguageService {

    private final UserRepository userRepo;
    private final OrganizationSettingsService settingsService;
    private final Messages messages;
    private final Map<UUID, String> cache = new ConcurrentHashMap<>();

    public UserLanguageServiceImpl(UserRepository userRepo, OrganizationSettingsService settingsService,
                                   MessageSource messageSource) {
        this.userRepo = userRepo;
        this.settingsService = settingsService;
        this.messages = new Messages(messageSource);
    }

    @Override
    public Locale localeOf(UUID userId) {
        if (userId == null) return null;
        String tag = cache.computeIfAbsent(userId, id -> userRepo.findById(id)
            .map(UserEntity::getLanguage)
            .orElse(null));
        return SupportedLocales.parse(tag);
    }

    @Override
    public String organizationDefaultLanguage() {
        String tag = settingsService.publicBranding().defaultLanguage();
        return SupportedLocales.isSupported(tag) ? tag : null;
    }

    @Override
    @Transactional
    public String updateLanguage(UUID userId, String language) {
        String normalized = SupportedLocales.normalize(language);
        if (normalized == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, messages.get(
                "error.language.unsupported", language, String.join(", ", SupportedLocales.SUPPORTED_TAGS)));
        }
        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND,
                messages.get("error.user.not.found")));
        user.setLanguage(normalized);
        userRepo.save(user);
        cache.put(userId, normalized);
        return normalized;
    }
}
