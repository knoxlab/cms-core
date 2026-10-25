package com.powercms.cms.identity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站点能力键目录下发（FR-IAM-004）+ 前端兜底清单对齐守护：
 * 本测试锁定：① ALL 完整性；② 目录接口可达且含全部键；
 * ③ fallback 清单与 ALL 逐字一致（防再次漂移）。
 */
class SiteCapabilityCatalogTest {

    /** 与 admin-ui/src/pages/Identity.tsx 的 SITE_CAPABILITY_FALLBACK 保持逐字同步。 */
    private static final List<String> FRONTEND_FALLBACK = List.of(
        "read", "write", "review", "deploy", "model", "template", "migrate", "admin",
        "stats", "stats-admin", "logs", "logs-admin");

    @Test
    void allContainsEveryKnownCapability() {
        assertThat(SiteCapabilities.ALL).contains(
            "read", "write", "review", "deploy", "model", "template", "migrate", "admin",
            "stats", "stats-admin", "logs", "logs-admin");
    }

    @Test
    void frontendFallbackMatchesBackendAll() {
        // 逐项双向对齐：后端新增能力而前端未跟进（或反之）即在此失败
        assertThat(SiteCapabilities.ALL).containsExactlyInAnyOrderElementsOf(FRONTEND_FALLBACK);
    }

    @Test
    void parseAcceptsEveryCatalogKey() {
        // 目录里的每个键都必须能通过 parse()（未登记键会被静默丢弃，R-01 同类缺陷）
        var parsed = SiteCapabilities.parse(String.join(",", FRONTEND_FALLBACK));
        assertThat(parsed).containsExactlyInAnyOrderElementsOf(FRONTEND_FALLBACK);
    }

    @Test
    void builtInRolesOnlyUseRegisteredKeys() {
        // 内置角色矩阵不得引用未登记的能力键（parse/copyOf 会静默丢弃）
        for (String code : List.of(BuiltInRoleCatalog.SYSTEM_ADMIN, BuiltInRoleCatalog.SITE_ADMIN,
            BuiltInRoleCatalog.EDITOR, BuiltInRoleCatalog.REVIEWER, BuiltInRoleCatalog.PUBLISHER)) {
            Set<String> caps = BuiltInRoleCatalog.capabilities(code);
            assertThat(caps).allSatisfy(c -> assertThat(SiteCapabilities.ALL).contains(c));
        }
    }
}
