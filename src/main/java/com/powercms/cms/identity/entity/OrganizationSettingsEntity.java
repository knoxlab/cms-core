package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 组织安全与品牌配置（spec 5.5 / 8.8，P1）：loginIdentifier、品牌、发件等。
 * 1:1 组织；缺失时按默认值返回。
 */
@Entity
@Table(name = "organization_settings")
public class OrganizationSettingsEntity {

    public static final String LOGIN_IDENTIFIER_USERNAME = "USERNAME";
    public static final String LOGIN_IDENTIFIER_EMAIL = "EMAIL";

    @Id
    private UUID id; // organization.id

    @Column(name = "login_identifier", nullable = false, length = 20)
    private String loginIdentifier = LOGIN_IDENTIFIER_USERNAME;

    @Column(name = "portal_url", length = 500)
    private String portalUrl;

    @Column(name = "mail_domain", length = 200)
    private String mailDomain;

    @Column(name = "from_email", length = 320)
    private String fromEmail;

    @Column(name = "default_language", length = 20)
    private String defaultLanguage = "zh-CN";

    @Column(name = "default_timezone", length = 60)
    private String defaultTimezone = "Asia/Shanghai";

    /** JSON：backendPrimaryColor / backendSecondaryColor / loginBackground（spec 5.5）。 */
    @Column(length = 2000)
    private String branding;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }

    public static OrganizationSettingsEntity defaults(UUID organizationId) {
        OrganizationSettingsEntity e = new OrganizationSettingsEntity();
        e.id = organizationId;
        return e;
    }

    public UUID getId() { return id; }
    public String getLoginIdentifier() { return loginIdentifier; }
    public void setLoginIdentifier(String loginIdentifier) { this.loginIdentifier = loginIdentifier; }
    public String getPortalUrl() { return portalUrl; }
    public void setPortalUrl(String portalUrl) { this.portalUrl = portalUrl; }
    public String getMailDomain() { return mailDomain; }
    public void setMailDomain(String mailDomain) { this.mailDomain = mailDomain; }
    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }
    public String getDefaultLanguage() { return defaultLanguage; }
    public void setDefaultLanguage(String defaultLanguage) { this.defaultLanguage = defaultLanguage; }
    public String getDefaultTimezone() { return defaultTimezone; }
    public void setDefaultTimezone(String defaultTimezone) { this.defaultTimezone = defaultTimezone; }
    public String getBranding() { return branding; }
    public void setBranding(String branding) { this.branding = branding; }
    public Instant getUpdatedAt() { return updatedAt; }
}
