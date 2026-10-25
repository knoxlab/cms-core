package com.powercms.cms.systemlog;

import com.powercms.cms.identity.SiteCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-LOG-020（R-01 能力键生效）：logs / logs-admin 必须登记进 SiteCapabilities.ALL，
 * 否则 SiteCapabilities.parse() 会静默丢弃、角色赋权失效。
 */
class LogsCapabilityRegistrationTest {

    @Test
    void logsKeysAreRegisteredInAll() {
        assertThat(SiteCapabilities.ALL).contains("logs", "logs-admin");
    }

    @Test
    void parseKeepsLogsCapability() {
        // 未登记时此断言失败：parse() 丢弃不在 ALL 中的键（评审 R-01 阻断项）
        var parsed = SiteCapabilities.parse("[\"logs\",\"logs-admin\",\"read\"]");
        assertThat(parsed).contains(SiteCapabilities.LOGS, SiteCapabilities.LOGS_ADMIN);
    }

    @Test
    void systemAdminMatrixContainsBothLogsKeys() {
        var caps = com.powercms.cms.identity.BuiltInRoleCatalog
            .capabilities(com.powercms.cms.identity.BuiltInRoleCatalog.SYSTEM_ADMIN);
        assertThat(caps).contains("logs", "logs-admin");
    }

    @Test
    void siteAdminHasLogsButNotLogsAdmin() {
        var caps = com.powercms.cms.identity.BuiltInRoleCatalog
            .capabilities(com.powercms.cms.identity.BuiltInRoleCatalog.SITE_ADMIN);
        assertThat(caps).contains("logs");
        assertThat(caps).doesNotContain("logs-admin");
    }

    @Test
    void editorHasNoLogsCapability() {
        var caps = com.powercms.cms.identity.BuiltInRoleCatalog
            .capabilities(com.powercms.cms.identity.BuiltInRoleCatalog.EDITOR);
        assertThat(caps).doesNotContain("logs", "logs-admin");
    }
}
