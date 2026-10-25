package com.powercms.cms.stats.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-STAT-066/069/094/107 验收：查询端点鉴权注解——`stats` 看报表与曲线，
 * `stats-admin` 看观看明细与节点管理。
 */
class AnalyticsQueryControllerSecurityTest {

    private final Class<?> controller = AnalyticsQueryController.class;

    @Test
    void overviewRequiresStats() {
        Method m = method("overview");
        assertThat(m.getAnnotation(GetMapping.class).value()).containsExactly("/overview");
        assertThat(m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.check(authentication, #siteId, 'stats')");
    }

    @Test
    void realtimeSummaryRequiresStats() {
        Method m = method("realtimeSummary");
        assertThat(m.getAnnotation(GetMapping.class).value()).containsExactly("/realtime/summary");
        assertThat(m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.check(authentication, #siteId, 'stats')");
    }

    @Test
    void nodesRequiresStatsAdmin() {
        Method m = method("nodes");
        assertThat(m.getAnnotation(GetMapping.class).value()).containsExactly("/nodes");
        assertThat(m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.check(authentication, #siteId, 'stats-admin')");
    }

    @Test
    void drainRequiresStatsAdmin() {
        Method m = method("drain");
        assertThat(m.getAnnotation(PostMapping.class).value()).containsExactly("/nodes/{serverNode}/drain");
        assertThat(m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.check(authentication, #siteId, 'stats-admin')");
    }

    @Test
    void watchingListRequiresStatsAdmin() {
        Method m = method("realtimeWatching");
        assertThat(m.getAnnotation(GetMapping.class).value()).containsExactly("/realtime/watching");
        assertThat(m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.check(authentication, #siteId, 'stats-admin')");
    }

    @Test
    void collectEndpointIsUnderDeliveryPrefix() throws Exception {
        Class<?> collect = Class.forName("com.powercms.cms.stats.web.AnalyticsCollectController");
        RequestMapping mapping = collect.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/v1/delivery/analytics");
        Method collectMethod = Arrays.stream(collect.getDeclaredMethods())
            .filter(m -> m.getName().equals("collect")).findFirst().orElseThrow();
        assertThat(collectMethod.getAnnotation(PostMapping.class).value()).containsExactly("/collect");
        assertThat(collectMethod.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class)).isNull();
    }

    private Method method(String name) {
        return Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }
}
