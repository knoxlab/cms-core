package com.powercms.cms.publish.web;

import com.powercms.cms.publish.ReleaseMonitorService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 发布监控与异常自动回滚端点（FR-AI-AGENT-004）。
 *
 * <p>路径：{@code /api/v1/sites/{siteId}/release-monitor}
 * <ul>
 *   <li>{@code GET/PUT /policy}：监控策略（默认关闭）；变更须 {@code deploy} 能力；</li>
 *   <li>{@code GET /incidents}：失败/劣化/演练事件；</li>
 *   <li>{@code POST /drill}：回滚策略演练（评估影响并留档，不真正回退）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/release-monitor")
public class ReleaseMonitorController {

    private final ReleaseMonitorService monitorService;

    public ReleaseMonitorController(ReleaseMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/policy")
    ReleaseMonitorService.PolicyView getPolicy(@PathVariable UUID siteId) {
        return monitorService.getPolicy(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'deploy')")
    @PutMapping("/policy")
    ReleaseMonitorService.PolicyView updatePolicy(@PathVariable UUID siteId,
                                                  @RequestBody ReleaseMonitorService.PolicyUpdate request) {
        return monitorService.updatePolicy(siteId, request, currentUsername());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/incidents")
    List<ReleaseMonitorService.IncidentView> listIncidents(@PathVariable UUID siteId) {
        return monitorService.listIncidents(siteId);
    }

    /** 各发布目标当前部署与健康度（只读；与 MCP 工具 get_release_health 同一出口）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/health")
    List<ReleaseMonitorService.TargetHealth> health(@PathVariable UUID siteId) {
        return monitorService.currentHealth(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'deploy')")
    @PostMapping("/drill")
    ReleaseMonitorService.IncidentView drill(@PathVariable UUID siteId,
                                             @RequestBody(required = false) DrillRequest request) {
        UUID deploymentId = request == null ? null : request.deploymentId();
        return monitorService.recordDrill(siteId, deploymentId, currentUsername());
    }

    /** 演练入参：可选指定部署记录，缺省取站点当前已部署发布。 */
    public record DrillRequest(UUID deploymentId) {}

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
            && authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.UserDetails user) {
            return user.getUsername();
        }
        throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "未认证");
    }
}
