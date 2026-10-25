package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Locale;

/** 语言偏好切换请求（FR-I18N-004）：如 zh-CN / en-US / ja-JP / ko-KR / fr-FR / es-ES。 */
public record UpdateLanguageRequest(
    @NotBlank String language
) {
    public UpdateLanguageRequest {
        if (language != null) {
            Locale.forLanguageTag(language.trim());
        }
    }
}
