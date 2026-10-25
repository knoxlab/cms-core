package com.powercms.cms.identity;

import java.util.Locale;
import java.util.UUID;

/**
 * 语言偏好读写（FR-I18N-004）：供 {@code UserLocaleFilter} 每请求解析，
 * 实现需自缓存避免每请求查库。
 */
public interface UserLanguageService {

    /** 用户持久化语言偏好；未设置返回 null（跟随组织默认）。 */
    Locale localeOf(UUID userId);

    /** 组织默认语言标签（organization_settings.default_language）；无组织返回 null。 */
    String organizationDefaultLanguage();

    /** 校验并保存用户语言偏好；非法语言抛 BusinessException(VALIDATION_FAILED)。 */
    String updateLanguage(UUID userId, String language);
}
