package com.powercms.cms.platform.web;

import com.powercms.cms.platform.i18n.I18nConfig;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerI18nTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(new I18nConfig().messageSource());

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void businessExceptionMessageIsLocalizedFromChineseSourceText() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("en-US"));
        var response = handler.handleBusiness(
            new BusinessException(ErrorCodes.UNAUTHENTICATED, "用户名或密码错误"));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.UNAUTHENTICATED);
        assertThat(response.getBody().message()).isEqualTo("Incorrect username or password");
    }

    @Test
    void frameworkKeysFollowLocaleContext() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr-FR"));
        var response = handler.handleBadRequest(new RuntimeException("ignored"));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Paramètres de requête invalides");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("ja-JP"));
        var notFound = handler.handleMissingResource(new RuntimeException("ignored"));
        assertThat(notFound.getBody()).isNotNull();
        assertThat(notFound.getBody().message()).contains("リソース");
    }

    /**
     * FR-IAM-002：登录失败的稳定键必须按语言渲染，且占位符 {@code {0}}（剩余尝试次数）
     * 被真正替换；法语的 {@code d''utilisateur} 须经 MessageFormat 还原为单撇号。
     */
    @Test
    void loginFailureKeysRenderPerLanguageWithAttemptsSubstituted() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("zh-CN"));
        var zh = handler.handleBusiness(BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
            "用户名或密码错误，还可尝试 3 次", "error.auth.login.remaining", 3));
        assertThat(zh.getBody()).isNotNull();
        assertThat(zh.getBody().message()).isEqualTo("用户名或密码错误，还可尝试 3 次");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("en-US"));
        var en = handler.handleBusiness(BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
            "用户名或密码错误，还可尝试 3 次", "error.auth.login.remaining", 3));
        assertThat(en.getBody()).isNotNull();
        assertThat(en.getBody().message()).isEqualTo("Incorrect username or password, 3 attempts remaining");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr-FR"));
        var fr = handler.handleBusiness(BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
            "用户名或密码错误，还可尝试 3 次", "error.auth.login.remaining", 3));
        assertThat(fr.getBody()).isNotNull();
        assertThat(fr.getBody().message())
            .isEqualTo("Nom d'utilisateur ou mot de passe incorrect, 3 tentatives restantes");

        // 账号锁定文案（无占位符）同样按键本地化
        LocaleContextHolder.setLocale(Locale.forLanguageTag("en-US"));
        var locked = handler.handleBusiness(BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
            "账号已被锁定，请联系管理员解锁", "error.auth.login.locked"));
        assertThat(locked.getBody()).isNotNull();
        assertThat(locked.getBody().message())
            .isEqualTo("Account locked. Contact an administrator to unlock it.");
    }

    /**
     * P1 G-5：授权异常的稳定键必须按 locale 渲染，且占位符被真正替换——
     * {@code {0}}=版本枚举名（ASCII，非中文 displayName）、{@code {1}}=能力位枚举名、
     * {@code {0..4}}=配额键/上限/当前/追加/单位；法语资源包里的 {@code l''edition}
     * 须经 MessageFormat 还原为单撇号 {@code l'edition}。
     */
    @Test
    void licenseKeysRenderPerLanguageWithEditionAndQuotaSubstituted() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("zh-CN"));
        var zh = handler.handleBusiness(BusinessException.keyed(ErrorCodes.LICENSE_CAPABILITY_DISABLED,
            "当前版本为「专业版」，未授权能力：KNOX_MEDIA", "error.license.capability.disabled",
            "PROFESSIONAL", "KNOX_MEDIA"));
        assertThat(zh.getBody()).isNotNull();
        assertThat(zh.getBody().message())
            .isEqualTo("当前版本 PROFESSIONAL 未授权能力 KNOX_MEDIA，请升级版本或调整授权");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("en-US"));
        var en = handler.handleBusiness(BusinessException.keyed(ErrorCodes.LICENSE_CAPABILITY_DISABLED,
            "当前版本为「专业版」，未授权能力：KNOX_MEDIA", "error.license.capability.disabled",
            "PROFESSIONAL", "KNOX_MEDIA"));
        assertThat(en.getBody()).isNotNull();
        assertThat(en.getBody().message())
            .isEqualTo("Capability KNOX_MEDIA is not licensed for edition PROFESSIONAL. "
                + "Upgrade the edition or adjust the license.");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr-FR"));
        var fr = handler.handleBusiness(BusinessException.keyed(ErrorCodes.LICENSE_CAPABILITY_DISABLED,
            "当前版本为「专业版」，未授权能力：KNOX_MEDIA", "error.license.capability.disabled",
            "PROFESSIONAL", "KNOX_MEDIA"));
        assertThat(fr.getBody()).isNotNull();
        assertThat(fr.getBody().message())
            .isEqualTo("La capacite KNOX_MEDIA n'est pas licenciee pour l'edition PROFESSIONAL. "
                + "Mettez a niveau l'edition ou ajustez la licence.");
        // 撇号还原：资源包里写 l''edition，MessageFormat 输出单个 l'edition
        assertThat(fr.getBody().message()).contains("n'est", "l'edition PROFESSIONAL");

        // 配额占位符：{0}=配额键、{1}={2}={3}=数字、{4}=单位被真正替换
        LocaleContextHolder.setLocale(Locale.forLanguageTag("en-US"));
        var quota = handler.handleBusiness(BusinessException.keyed(ErrorCodes.LICENSE_QUOTA_EXCEEDED,
            "站点数 已达上限 10；当前 10 个", "error.license.quota.exceeded",
            "sites", 10, 10, 1, "个"));
        assertThat(quota.getBody()).isNotNull();
        assertThat(quota.getBody().message()).isEqualTo(
            "Quota sites has reached its licensed limit of 10 (current 10, adding 1 个). "
                + "Expand the quota or upgrade the edition; read, search and export remain available.");
    }
}
