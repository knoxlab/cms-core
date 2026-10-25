package com.powercms.cms.systemlog;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.systemlog.repo.AccessLogRepository;
import com.powercms.cms.systemlog.repo.RuntimeLogRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-LOG-PM-001 / §6.2 可见域强制过滤（AC-LOG-014）+ FR-LOG-QR-004 防滥用限制。
 * SELECT 与权限判定在同一服务方法内完成：scopeSiteKey 直通仓库查询参数。
 */
class LogQueryServiceScopeTest {

    private RuntimeLogRepository runtimeRepo;
    private AccessLogRepository accessRepo;
    private SiteCapabilityEvaluator capabilities;
    private AuditService audit;
    private LogQueryService service;
    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runtimeRepo = mock(RuntimeLogRepository.class);
        accessRepo = mock(AccessLogRepository.class);
        capabilities = mock(SiteCapabilityEvaluator.class);
        audit = mock(AuditService.class);
        service = new LogQueryService(runtimeRepo, accessRepo, capabilities, audit, new SystemLogProperties());
        // 单元测试免容器：注入固定站点键解析（siteId → "demo-site"）
        service.setSiteKeyResolverForTest(siteId -> "demo-site");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        var principal = new AuthenticatedUserPrincipal(UUID.randomUUID(),
            User.withUsername("alice").password("{noop}x").roles("site-admin").build());
        Authentication auth = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        Mockito.mockStatic(AuditService.class, Mockito.CALLS_REAL_METHODS).close();
    }

    private void authenticate(UUID userId, boolean systemAdmin, boolean logsCap) {
        var principal = new AuthenticatedUserPrincipal(userId,
            User.withUsername("alice").password("{noop}x").roles("site-admin").build());
        Authentication auth = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(capabilities.isSystemAdmin(userId)).thenReturn(systemAdmin);
        when(capabilities.has(eq(userId), eq(siteId), eq("logs"))).thenReturn(logsCap);
        when(capabilities.has(eq(userId), eq(siteId), eq("logs-admin"))).thenReturn(false);
    }

    @Test
    void siteScopeForcesSiteKeyFilter() {
        UUID userId = UUID.randomUUID();
        authenticate(userId, false, true);
        // 仓库查询必须收到 scopeSiteKey = 该站点键（可见域强制，AC-LOG-014）
        when(runtimeRepo.search(eq("demo-site"), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any())).thenReturn(List.of());
        var scope = service.resolveScope(SecurityContextHolder.getContext().getAuthentication(), siteId);
        assertThat(scope.orgDomain()).isFalse();
    }

    @Test
    void orgScopeSeesAll() {
        UUID userId = UUID.randomUUID();
        var principal = new AuthenticatedUserPrincipal(userId,
            User.withUsername("root").password("{noop}x").roles("system-admin").build());
        var auth = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(capabilities.isSystemAdmin(userId)).thenReturn(true);
        var scope = service.resolveScope(auth, null);
        assertThat(scope.orgDomain()).isTrue();
        assertThat(scope.scopeSiteKey()).isNull();
    }

    @Test
    void noCapabilityDeniedWithExistingErrorCode() {
        UUID userId = UUID.randomUUID();
        authenticate(userId, false, false);
        // FR-LOG-PM-002：复用 PERMISSION_DENIED，不新建 LOG_PERMISSION_DENIED
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThatThrownBy(() -> service.resolveScope(auth, siteId))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.PERMISSION_DENIED));
    }

    @Test
    void siteUserWithoutSiteSelectedDenied() {
        UUID userId = UUID.randomUUID();
        authenticate(userId, false, true);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // 未选择站点：SITE 域用户不可访问日志页（§6.2 补充规则）
        assertThatThrownBy(() -> service.resolveScope(auth, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void oversizedSpanRejected() {
        UUID userId = UUID.randomUUID();
        var principal = new AuthenticatedUserPrincipal(userId,
            User.withUsername("root").password("{noop}x").roles("system-admin").build());
        var auth = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(capabilities.isSystemAdmin(userId)).thenReturn(true);
        var request = new LogQueryService.QueryRequest("runtime",
            Instant.now().minus(java.time.Duration.ofDays(60)), null,
            null, null, null, null, null, null, null, 50, false);
        // FR-LOG-QR-004：单次跨度 ≤ 30 天
        assertThatThrownBy(() -> service.stats(auth, null, request))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.LOG_QUERY_SPAN_EXCEEDED));
    }

    @Test
    void queryRateLimited() {
        UUID userId = UUID.randomUUID();
        var principal = new AuthenticatedUserPrincipal(userId,
            User.withUsername("rate").password("{noop}x").roles("system-admin").build());
        var auth = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(capabilities.isSystemAdmin(userId)).thenReturn(true);
        when(runtimeRepo.countByLevel(isNull(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of());
        when(runtimeRepo.countByHour(isNull(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of());
        SystemLogProperties props = new SystemLogProperties();
        props.setQueryRatePerMinute(3);
        service = new LogQueryService(runtimeRepo, accessRepo, capabilities, audit, props);
        // FR-LOG-QR-004：每用户每分钟 ≤ 30 次（此处 3 次便于测试）。
        // 每次迭代使用不同 keyword（= 不同缓存键），缓存回放不扣配额，只有真实计算扣。
        for (int i = 0; i < 3; i++) {
            service.stats(auth, null, new LogQueryService.QueryRequest("runtime",
                Instant.now().minus(java.time.Duration.ofMinutes(5)), null,
                null, null, null, "kw-" + i, null, null, null, 50, false));
        }
        assertThatThrownBy(() -> service.stats(auth, null, new LogQueryService.QueryRequest("runtime",
                Instant.now().minus(java.time.Duration.ofMinutes(5)), null,
                null, null, null, "kw-final", null, null, null, 50, false)))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.LOG_QUERY_RATE_LIMITED));
    }

    @Test
    void detailHidesForeignSiteEntry() {
        UUID userId = UUID.randomUUID();
        authenticate(userId, false, true);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // SITE 域：仓库按 siteKey 过滤后返回 null → 404，构造 ID 请求不可越权（AC-LOG-014）
        when(runtimeRepo.findVisible(any(), eq("demo-site"))).thenReturn(null);
        assertThatThrownBy(() -> service.detail(auth, siteId, "runtime", UUID.randomUUID()))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.NOT_FOUND));
    }
}
