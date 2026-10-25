package com.powercms.cms.systemlog.web;

import com.powercms.cms.systemlog.LogQueryService;
import com.powercms.cms.systemlog.SystemLogProperties;
import com.powercms.cms.systemlog.SystemLogPipeline;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 系统日志查询 API（FR-LOG-QR / FR-LOG-PM）。
 *
 * <p>鉴权（FR-LOG-PM-002）：路由级 {@code @PreAuthorize} 要求 {@code logs} 或 {@code logs-admin}
 * 能力；可见域在 {@link LogQueryService#resolveScope} 内与 SELECT 同方法强制（§6.2）。
 * 只读面：无任何修改/删除单条接口（FR-LOG-PM-003）。</p>
 */
@RestController
@RequestMapping("/api/v1/system-logs")
public class SystemLogController {

    private final LogQueryService queryService;
    private final SystemLogProperties props;
    private final SystemLogPipeline pipeline;

    public SystemLogController(LogQueryService queryService, SystemLogProperties props, SystemLogPipeline pipeline) {
        this.queryService = queryService;
        this.props = props;
        this.pipeline = pipeline;
    }

    public record QueryParams(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        String levels,
        String modules,
        String outcomes,
        String keyword,
        String traceId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant cursorTime,
        String cursorId,
        Integer pageSize,
        Boolean ascending) {}

    /** 列表检索（FR-LOG-QR-001～004）。默认时间窗 = 最近 1 小时。 */
    @GetMapping("/{type}")
    @PreAuthorize("hasAnyRole('system-admin','site-admin') or hasAuthority('SCOPE_logs')")
    public LogQueryService.LogPage search(Authentication authentication,
                                          @RequestParam(required = false) UUID siteId,
                                          @PathVariable String type,
                                          QueryParams params) {
        return queryService.search(authentication, siteId, toRequest(type, params));
    }

    /** 单条详情（FR-LOG-QR-006，JSON/原文由前端切换渲染）。 */
    @GetMapping("/{type}/{id}")
    @PreAuthorize("hasAnyRole('system-admin','site-admin') or hasAuthority('SCOPE_logs')")
    public Object detail(Authentication authentication,
                         @RequestParam(required = false) UUID siteId,
                         @PathVariable String type,
                         @PathVariable UUID id) {
        return queryService.detail(authentication, siteId, type, id);
    }

    /** 统计概览（FR-LOG-QR-005，60 秒缓存）。 */
    @GetMapping("/{type}/stats")
    @PreAuthorize("hasAnyRole('system-admin','site-admin') or hasAuthority('SCOPE_logs')")
    public LogQueryService.StatsResponse stats(Authentication authentication,
                                               @RequestParam(required = false) UUID siteId,
                                               @PathVariable String type,
                                               QueryParams params) {
        return queryService.stats(authentication, siteId, toRequest(type, params));
    }

    /** 来源模块聚合（FR-LOG-QR-001 条件⑤，上限 200 项）。 */
    @GetMapping("/modules")
    @PreAuthorize("hasAnyRole('system-admin','site-admin') or hasAuthority('SCOPE_logs')")
    public List<String> modules(Authentication authentication,
                                @RequestParam(required = false) UUID siteId,
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from) {
        return queryService.modules(authentication, siteId, from);
    }

    /** 同 traceId 关联日志（FR-LOG-QR-007 / FR-LOG-AC-006）。 */
    @GetMapping("/{type}/related/{traceId}")
    @PreAuthorize("hasAnyRole('system-admin','site-admin') or hasAuthority('SCOPE_logs')")
    public LogQueryService.LogPage related(Authentication authentication,
                                           @RequestParam(required = false) UUID siteId,
                                           @PathVariable String type,
                                           @PathVariable String traceId) {
        return queryService.related(authentication, siteId, type, traceId);
    }

    /**
     * 导出申请（FR-LOG-QR-008/009）：返回一次性短期 token 与失效时间；
     * 导出动作已在服务层写入 audit_event。
     */
    @GetMapping("/{type}/export")
    @PreAuthorize("hasRole('system-admin')")
    public LogQueryService.ExportResponse export(Authentication authentication,
                                                 @RequestParam(required = false) UUID siteId,
                                                 @PathVariable String type,
                                                 @RequestParam(defaultValue = "csv") String format,
                                                 QueryParams params) {
        return queryService.export(authentication, siteId, toRequest(type, params), format);
    }

    /** 导出下载（FR-LOG-QR-009）：一次性 token，30 分钟失效；文件首行附生成说明。 */
    @GetMapping("/{type}/export/{token}")
    @PreAuthorize("hasRole('system-admin')")
    public ResponseEntity<byte[]> download(Authentication authentication,
                                           @RequestParam(required = false) UUID siteId,
                                           @PathVariable String type,
                                           @PathVariable String token,
                                           @RequestParam(defaultValue = "csv") String format) {
        String content = queryService.exportFile(authentication, siteId, token, format);
        String filename = "powercms-" + type + "-log." + ("json".equalsIgnoreCase(format) ? "json" : "csv");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(content.getBytes(StandardCharsets.UTF_8));
    }

    /** 管道观测指标（NFR-LOG-004 丢弃计数，供健康检查/运维面板）。 */
    @GetMapping("/pipeline/metrics")
    @PreAuthorize("hasRole('system-admin')")
    public java.util.Map<String, Object> pipelineMetrics() {
        return pipeline.metrics();
    }

    private static LogQueryService.QueryRequest toRequest(String type, QueryParams p) {
        return new LogQueryService.QueryRequest(
            type,
            p.from() == null ? Instant.now().minus(java.time.Duration.ofHours(1)) : p.from(),
            p.to(),
            split(p.levels()),
            split(p.modules()),
            split(p.outcomes()),
            p.keyword(),
            p.traceId(),
            p.cursorTime(),
            parseUuid(p.cursorId()),
            p.pageSize(),
            p.ascending());
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return List.of(csv.split(","));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
