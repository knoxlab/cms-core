package com.powercms.cms.platform.web;

import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-CV-VER-010：时间机器只读态下「写请求返回 403」。
 *
 * <p>只读标记由 {@code X-PowerCMS-Read-Only-Snapshot} 头声明（前端仅在时间机器页面挂载期间携带），
 * 因此判定是逐请求的：不带头的写请求一切照旧，不会因为用户看过一次历史快照就再也写不了内容。</p>
 */
class TimeMachineReadOnlyInterceptorTest {

    private final TimeMachineReadOnlyInterceptor.Guard guard = new TimeMachineReadOnlyInterceptor.Guard();

    private MockHttpServletRequest request(String method, String snapshotAt) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/sites/x/contents");
        if (snapshotAt != null) request.addHeader(TimeMachineReadOnlyInterceptor.HEADER, snapshotAt);
        return request;
    }

    @Test
    void blocksPostWhileSnapshotHeaderPresent() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> guard.preHandle(request("POST", "2026-09-18T10:00:00Z"), new MockHttpServletResponse(), new Object()));

        assertEquals("TIME_MACHINE_READ_ONLY", ex.getCode());
        assertTrue(ex.getMessage().contains("只读"));
    }

    @Test
    void blocksPutPatchAndDeleteToo() {
        for (String method : new String[] {"PUT", "PATCH", "DELETE"}) {
            assertEquals("TIME_MACHINE_READ_ONLY", assertThrows(BusinessException.class,
                () -> guard.preHandle(request(method, "2026-09-18T10:00:00Z"), new MockHttpServletResponse(), new Object()))
                .getCode(), method);
        }
    }

    @Test
    void allowsReadMethodsWhileSnapshotHeaderPresent() {
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            assertTrue(guard.preHandle(request(method, "2026-09-18T10:00:00Z"), new MockHttpServletResponse(), new Object()),
                method);
        }
    }

    @Test
    void allowsWritesWhenSnapshotHeaderAbsent() {
        assertTrue(guard.preHandle(request("POST", null), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void blankHeaderIsNotAReadOnlyDeclaration() {
        assertTrue(guard.preHandle(request("POST", "   "), new MockHttpServletResponse(), new Object()));
    }
}
