package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.OrganizationSettingsEntity;
import com.powercms.cms.workflow.SystemMailContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link SystemMailContext} 的 identity 实现（DEF-CFG-03）：把组织设置里的
 * {@code fromEmail} / {@code mailDomain} / {@code portalUrl} 接进真实发信链路。
 *
 * <p>修复前这三个字段是「能存、能读、无行为」的空转配置 —— 唯一发信方
 * {@code EmailNotificationChannel} 只认静态配置 {@code powercms.mail.from}，管理员在
 * 系统配置里改了发件地址却不会有任何邮件表现（域 11 报告 §3.3）。
 *
 * <p>组织口径复用 {@link OrganizationSettingsService#defaultOrganizationId()}（DEF-CFG-01 已
 * 收敛为唯一口径），未配置组织时返回空、由调用方回退。
 */
@Component
class OrganizationSystemMailContext implements SystemMailContext {

    /**
     * 配了 {@code mailDomain} 但没配 {@code fromEmail} 时的本地部分。
     *
     * <p>与静态配置的缺省 {@code noreply@powercms.local} 同义，只是把域名换成组织自有的，
     * 从而让 {@code mailDomain} 单独配置也有意义；`no-reply` 是系统通知的通用惯例
     * （收件人回信无意义）。
     */
    static final String DEFAULT_LOCAL_PART = "noreply";

    private final OrganizationSettingsService settings;

    OrganizationSystemMailContext(OrganizationSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public Optional<String> fromAddress() {
        return settings().map(setting -> {
            String from = trimToNull(setting.getFromEmail());
            if (from != null) return from;
            String domain = trimToNull(setting.getMailDomain());
            return domain == null ? null : DEFAULT_LOCAL_PART + "@" + domain;
        });
    }

    @Override
    public Optional<String> portalUrl() {
        return settings().map(setting -> trimToNull(setting.getPortalUrl()));
    }

    /** 当前组织的设置行；无组织返回空。 */
    private Optional<OrganizationSettingsEntity> settings() {
        UUID orgId = settings.defaultOrganizationId();
        return orgId == null ? Optional.empty() : Optional.of(settings.settings(orgId));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
