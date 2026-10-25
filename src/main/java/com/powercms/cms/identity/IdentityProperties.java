package com.powercms.cms.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "powercms.identity")
public class IdentityProperties {

    /** Consecutive failed logins before LOCKED (FR-IAM-002). */
    private int maxFailedLogins = 5;

    /** Short delay after a failed login; 0 disables (tests). */
    private int failureDelayMs = 150;

    /** Production can set false so only session/Bearer remain. */
    private boolean httpBasicEnabled = true;

    /** Reject passwords that match the last N hashes (FR-IAM-003). */
    private int passwordHistorySize = 3;

    /** 0 = never expire. */
    private int passwordExpireDays = 0;

    /** 密码最短长度（spec 5.6，可配置）。 */
    private int passwordMinLength = 8;

    /** 复杂度：是否要求大写/小写/数字（spec 5.6，可配置）。 */
    private boolean passwordRequireUpper = true;
    private boolean passwordRequireLower = true;
    private boolean passwordRequireDigit = true;

    private String factoryPassword = "Admin@123456";

    /** When true, refuse startup if admin still uses the factory password. */
    private boolean rejectFactoryPassword = false;

    private int maxTokenTtlDays = 365;

    public int getMaxFailedLogins() { return maxFailedLogins; }
    public void setMaxFailedLogins(int maxFailedLogins) { this.maxFailedLogins = maxFailedLogins; }
    public int getFailureDelayMs() { return failureDelayMs; }
    public void setFailureDelayMs(int failureDelayMs) { this.failureDelayMs = failureDelayMs; }
    public boolean isHttpBasicEnabled() { return httpBasicEnabled; }
    public void setHttpBasicEnabled(boolean httpBasicEnabled) { this.httpBasicEnabled = httpBasicEnabled; }
    public int getPasswordHistorySize() { return passwordHistorySize; }
    public void setPasswordHistorySize(int passwordHistorySize) { this.passwordHistorySize = passwordHistorySize; }
    public int getPasswordExpireDays() { return passwordExpireDays; }
    public void setPasswordExpireDays(int passwordExpireDays) { this.passwordExpireDays = passwordExpireDays; }
    public int getPasswordMinLength() { return passwordMinLength; }
    public void setPasswordMinLength(int passwordMinLength) { this.passwordMinLength = passwordMinLength; }
    public boolean isPasswordRequireUpper() { return passwordRequireUpper; }
    public void setPasswordRequireUpper(boolean passwordRequireUpper) { this.passwordRequireUpper = passwordRequireUpper; }
    public boolean isPasswordRequireLower() { return passwordRequireLower; }
    public void setPasswordRequireLower(boolean passwordRequireLower) { this.passwordRequireLower = passwordRequireLower; }
    public boolean isPasswordRequireDigit() { return passwordRequireDigit; }
    public void setPasswordRequireDigit(boolean passwordRequireDigit) { this.passwordRequireDigit = passwordRequireDigit; }
    public String getFactoryPassword() { return factoryPassword; }
    public void setFactoryPassword(String factoryPassword) { this.factoryPassword = factoryPassword; }
    public boolean isRejectFactoryPassword() { return rejectFactoryPassword; }
    public void setRejectFactoryPassword(boolean rejectFactoryPassword) { this.rejectFactoryPassword = rejectFactoryPassword; }
    public int getMaxTokenTtlDays() { return maxTokenTtlDays; }
    public void setMaxTokenTtlDays(int maxTokenTtlDays) { this.maxTokenTtlDays = maxTokenTtlDays; }
}
