package com.powercms.cms.platform.web;

import com.powercms.cms.audit.AuditDetail;
import com.powercms.cms.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.method.HandlerMethod;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ManagementAuditInterceptorTests {
    @Test
    void recordsAuthenticatedManagementRead() throws Exception {
        // AuditDetail 是**线程局部**：同一线程在别的用例里挂载过说明文本后，
        // 本用例的 afterCompletion 会读到那段残留（实测 detail 变成
        // "状态 DRAFT → PUBLISHED" 而非 null）。这里先清空，使断言只反映本用例的行为。
        // 拦截器本身在 afterCompletion 里用 getAndClear() 清理，因此这不是产品缺陷，
        // 而是「用例间通过 ThreadLocal 相互影响」——测试隔离问题。
        AuditDetail.getAndClear();

        AuditService auditService = mock(AuditService.class);
        var interceptor = new ManagementAuditInterceptor.Recorder(auditService);
        var request = new MockHttpServletRequest("GET", "/api/v1/sites");
        request.setUserPrincipal(new UsernamePasswordAuthenticationToken("operator", "n/a", List.of()));
        var response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new ManagementHandler(),
            ManagementHandler.class.getDeclaredMethod("list"));

        interceptor.afterCompletion(request, response, handler, null);

        verify(auditService).write(org.mockito.ArgumentMatchers.eq("operator"),
            org.mockito.ArgumentMatchers.eq("GET list"), org.mockito.ArgumentMatchers.eq("ManagementHandler"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("SUCCESS"), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull());
    }

    static class ManagementHandler { void list() {} }
}
