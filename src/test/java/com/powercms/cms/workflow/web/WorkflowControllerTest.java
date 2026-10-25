package com.powercms.cms.workflow.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowControllerTest {

    @Test
    void pendingTasksUsesAuthenticatedUserAndOnlyAllowsSiteScopeParam() throws Exception {
        Method pending = Arrays.stream(WorkflowController.class.getDeclaredMethods())
            .filter(m -> "pending".equals(m.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(pending.getAnnotation(GetMapping.class).value()).contains("/tasks/pending");
        // 站点范围可传，但必须通过站点 read 授权；身份始终取自登录态
        assertThat(pending.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("isAuthenticated() and (#siteId == null or @siteAuthorization.check(authentication, #siteId, 'read'))");
        var params = Arrays.stream(pending.getParameters())
            .filter(p -> p.isAnnotationPresent(RequestParam.class))
            .map(p -> p.getAnnotation(RequestParam.class).name())
            .toList();
        // 唯一允许的查询参数是 siteId（不得出现 assignee/username 之类的身份参数）
        assertThat(params).containsExactly("siteId");
    }

    @Test
    void cancelEndpointRequiresReviewPermission() throws Exception {
        Method cancel = Arrays.stream(WorkflowController.class.getDeclaredMethods())
            .filter(m -> "cancel".equals(m.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(cancel.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.task(authentication, #id, 'write') or @siteAuthorization.task(authentication, #id, 'admin')");
    }

    @Test
    void rescheduleEndpointRequiresReviewPermission() throws Exception {
        Method reschedule = Arrays.stream(WorkflowController.class.getDeclaredMethods())
            .filter(m -> "reschedule".equals(m.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(reschedule.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.task(authentication, #id, 'review')");
    }

    @Test
    void newVersionRequiresWorkflowAdmin() throws Exception {
        Method m = Arrays.stream(WorkflowController.class.getDeclaredMethods())
            .filter(x -> "newVersion".equals(x.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(m.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.workflow(authentication, #id, 'admin')");
    }

    @Test
    void notificationPreferencesRequireAuthentication() throws Exception {
        Method prefs = Arrays.stream(WorkflowController.class.getDeclaredMethods())
            .filter(m -> "preferences".equals(m.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(prefs.getAnnotation(PreAuthorize.class).value()).isEqualTo("isAuthenticated()");
        assertThat(prefs.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())
            .contains("/me/notification-preferences");
    }

    @Test
    void templateUpsertRequiresSiteAdmin() throws Exception {
        Method upsert = Arrays.stream(WorkflowController.class.getDeclaredMethods())
            .filter(m -> "upsertTemplate".equals(m.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(upsert.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("@siteAuthorization.check(authentication, #siteId, 'admin')");
    }
}
