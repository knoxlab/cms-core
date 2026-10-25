package com.powercms.cms.systemlog;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.SiteCapabilities;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.systemlog.entity.AccessLogEntity;
import com.powercms.cms.systemlog.entity.RuntimeLogEntity;
import com.powercms.cms.systemlog.repo.AccessLogRepository;
import com.powercms.cms.systemlog.repo.RuntimeLogRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志查询服务（FR-LOG-QR-001～011 / FR-LOG-PM-001～005）。
 *
 * <p><b>可见域强制过滤（§6.2）：</b>SELECT 与权限判定在**同一服务方法内**完成（防绕过）——
 * {@code system-admin}（ORG 域）可见全部站点与平台级条目；{@code logs} 持有者（SITE 域）
 * 仅可见 {@code site_key} = 当前站点的条目；平台级（site_key 为空）条目对 SITE 域不可见。</p>
 *
 * <p><b>防滥用（FR-LOG-QR-004）：</b>单次跨度 ≤ maxQuerySpan（默认 30 天）、
 * 每用户每分钟 ≤ queryRatePerMinute（默认 30 次），超限抛日志专属错误码。</p>
 *
 * <p><b>留痕（FR-LOG-PM-005）：</b>每次查询（含导出）经既有 {@code AuditService.write}
 * 写入 {@code audit_event}（objectType = RuntimeLog / AccessLog，action = QUERY / EXPORT），
 * 不新建留痕表。</p>
 */
@Service
public class LogQueryService {

    public static final String TYPE_RUNTIME = "runtime";
    public static final String TYPE_ACCESS = "access";

    private final RuntimeLogRepository runtimeRepo;
    private final AccessLogRepository accessRepo;
    private final SiteCapabilityEvaluator capabilities;
    private final AuditService audit;
    private final SystemLogProperties props;
    private final Map<String, Window> queryWindows = new ConcurrentHashMap<>();

    public LogQueryService(RuntimeLogRepository runtimeRepo,
                           AccessLogRepository accessRepo,
                           SiteCapabilityEvaluator capabilities,
                           AuditService audit,
                           SystemLogProperties props) {
        this.runtimeRepo = runtimeRepo;
        this.accessRepo = accessRepo;
        this.capabilities = capabilities;
        this.audit = audit;
        this.props = props;
    }

    // ───────────────────────── 可见域解析 ─────────────────────────

    /** 可见域判定结果：scopeSiteKey=null 表示 ORG 全量；非 null 仅可见该站点条目。 */
    public record Scope(boolean orgDomain, String scopeSiteKey) {}

    /**
     * 解析当前认证身份的可见域（FR-LOG-PM-001 / §6.2）。
     * 要求 {@code logs} 或 {@code logs-admin} 能力；无权限抛 PERMISSION_DENIED（复用既有码）。
     * siteId 可空：ORG 域用户可在未选站点时访问（平台级日志）。
     */
    public Scope resolveScope(Authentication authentication, UUID siteId) {
        UUID userId = userIdOf(authentication);
        if (userId == null) {
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "没有权限查看系统日志");
        }
        boolean systemAdmin = capabilities.isSystemAdmin(userId);
        if (systemAdmin) {
            return new Scope(true, null);
        }
        if (siteId == null) {
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "没有权限查看系统日志");
        }
        if (capabilities.has(userId, siteId, SiteCapabilities.LOGS)
            || capabilities.has(userId, siteId, SiteCapabilities.LOGS_ADMIN)) {
            return new Scope(false, requireSiteKey(siteId));
        }
        throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "没有权限查看系统日志");
    }

    private String requireSiteKey(UUID siteId) {
        // 循环依赖规避：站点键经 SiteRepository 读取（identity → site 为既有依赖方向）
        return siteKeyResolver.resolve(siteId);
    }

    /** 站点键解析器（构造期注入，测试可替换）。 */
    interface SiteKeyResolver {
        String resolve(UUID siteId);
    }

    private SiteKeyResolver siteKeyResolver;

    @org.springframework.beans.factory.annotation.Autowired
    void setSiteKeyResolver(com.powercms.cms.site.repo.SiteRepository siteRepo) {
        this.siteKeyResolver = siteId -> siteRepo.findById(siteId)
            .map(com.powercms.cms.site.entity.SiteEntity::getSiteKey)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在"));
    }

    /** 测试专用：单元测试直接构造服务时注入固定站点键解析（免 Spring 容器）。 */
    void setSiteKeyResolverForTest(SiteKeyResolver resolver) {
        this.siteKeyResolver = resolver;
    }

    private static UUID userIdOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        return authentication.getPrincipal() instanceof AuthenticatedUserPrincipal p ? p.getUserId() : null;
    }

    // ───────────────────────── 查询条件 ─────────────────────────

    public record QueryRequest(
        String type,
        Instant from,
        Instant to,
        List<String> levels,
        List<String> modules,
        List<String> outcomes,
        String keyword,
        String traceId,
        Instant cursorTime,
        UUID cursorId,
        Integer pageSize,
        Boolean ascending) {}

    public record LogPage(List<?> items, String nextCursorTime, String nextCursorId, boolean hasMore) {}

    // ───────────────────────── 检索 ─────────────────────────

    /**
     * 检索（FR-LOG-QR-001～004）：条件任意组合 + 稳定游标分页 + 防滥用限制 + audit 留痕。
     * 关键词以参数化方式参与匹配（FR-LOG-QR-002），不拼接 SQL 片段。
     */
    @Transactional(readOnly = true)
    public LogPage search(Authentication authentication, UUID siteId, QueryRequest request) {
        Scope scope = resolveScope(authentication, siteId);
        String actor = AuditService.currentActor();
        checkRateLimit(actor);
        validateSpan(request);

        int pageSize = Math.min(Math.max(request.pageSize() == null ? 50 : request.pageSize(), 1), 200);
        Pageable pageable = Boolean.TRUE.equals(request.ascending())
            ? PageRequest.of(0, pageSize, org.springframework.data.domain.Sort
                .by(org.springframework.data.domain.Sort.Direction.ASC, "occurredAt", "id"))
            : PageRequest.of(0, pageSize, org.springframework.data.domain.Sort
                .by(org.springframework.data.domain.Sort.Direction.DESC, "occurredAt", "id"));
        Instant to = request.to() == null ? Instant.now() : request.to();
        boolean runtime = !TYPE_ACCESS.equals(request.type());
        List<?> items;
        if (runtime) {
            items = runtimeRepo.search(scope.scopeSiteKey(), request.from(), to,
                normalize(request.levels()), normalize(request.modules()),
                minLevelRank(request.levels()), likePattern(request.keyword()),
                blankToNull(request.traceId()), request.cursorTime(), request.cursorId(), pageable)
                .stream().map(this::toRuntimeView).toList();
        } else {
            items = accessRepo.search(scope.scopeSiteKey(), request.from(), to,
                normalize(request.outcomes()), likePattern(request.keyword()),
                blankToNull(request.traceId()), request.cursorTime(), request.cursorId(), pageable)
                .stream().map(this::toAccessView).toList();
        }
        boolean hasMore = items.size() == pageSize;
        auditQuery(actor, runtime ? "RuntimeLog" : "AccessLog", "QUERY", request, items.size());
        return buildPage(items, hasMore, runtime);
    }

    /** 详情（FR-LOG-QR-006）：SITE 域对构造 ID 请求同样不可见（AC-LOG-014）。 */
    @Transactional(readOnly = true)
    public Object detail(Authentication authentication, UUID siteId, String type, UUID id) {
        resolveScope(authentication, siteId);
        boolean runtime = !TYPE_ACCESS.equals(type);
        Object entity = runtime
            ? runtimeRepo.findVisible(id, siteKeyForScope(authentication, siteId))
            : accessRepo.findVisible(id, siteKeyForScope(authentication, siteId));
        if (entity == null) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "日志条目不存在");
        }
        return runtime ? toRuntimeView(entity) : toAccessView(entity);
    }

    private String siteKeyForScope(Authentication authentication, UUID siteId) {
        Scope scope = resolveScope(authentication, siteId);
        return scope.scopeSiteKey();
    }

    // ───────────────────────── 统计概览 ─────────────────────────

    public record StatsResponse(long total, Map<String, Long> byLevel,
                                List<HourBucket> hourly, long cachedForSeconds, boolean cached) {}

    public record HourBucket(String hour, long total) {}

    /** 统计概览（FR-LOG-QR-005 / NFR-LOG-003）：结果缓存 60 秒。
     *  限流在缓存命中检查之后扣减 —— 缓存回放不消耗配额，避免前端周期性统计刷新挤占查询预算。 */
    @Transactional(readOnly = true)
    public StatsResponse stats(Authentication authentication, UUID siteId, QueryRequest request) {
        Scope scope = resolveScope(authentication, siteId);
        String actor = AuditService.currentActor();
        String cacheKey = statsKey(scope, request);
        Cached cached = statsCache.get(cacheKey);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt)) {
            return new StatsResponse(cached.value.total(), cached.value.byLevel(),
                cached.value.hourly(), 60, true);
        }
        checkRateLimit(actor);
        validateSpan(request);
        Instant to = request.to() == null ? now : request.to();
        boolean runtime = !TYPE_ACCESS.equals(request.type());
        List<?> counts;
        List<?> hours;
        if (runtime) {
            counts = runtimeRepo.countByLevel(scope.scopeSiteKey(), request.from(), to,
                normalize(request.levels()), normalize(request.modules()), minLevelRank(request.levels()),
                likePattern(request.keyword()), blankToNull(request.traceId()));
            hours = runtimeRepo.countByHour(scope.scopeSiteKey(), request.from(), to,
                normalize(request.levels()), normalize(request.modules()), minLevelRank(request.levels()),
                likePattern(request.keyword()), blankToNull(request.traceId()));
        } else {
            counts = accessRepo.countByOutcome(scope.scopeSiteKey(), request.from(), to,
                normalize(request.outcomes()), likePattern(request.keyword()), blankToNull(request.traceId()));
            hours = accessRepo.countByHour(scope.scopeSiteKey(), request.from(), to,
                normalize(request.outcomes()), likePattern(request.keyword()), blankToNull(request.traceId()));
        }
        long total = 0;
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (Object c : counts) {
            long v = c instanceof RuntimeLogRepository.LevelCount lc ? lc.getTotal()
                : ((AccessLogRepository.OutcomeCount) c).getTotal();
            String k = c instanceof RuntimeLogRepository.LevelCount l2 ? l2.getLevel()
                : ((AccessLogRepository.OutcomeCount) c).getOutcome();
            byKey.put(k, v);
            total += v;
        }
        List<HourBucket> buckets = new ArrayList<>();
        for (Object h : hours) {
            // 两个仓库各自嵌套定义 HourCount（不同类型），须按类型分流转换
            if (h instanceof RuntimeLogRepository.HourCount rh) {
                buckets.add(new HourBucket(rh.getHour().toInstant().toString(), rh.getTotal()));
            } else if (h instanceof AccessLogRepository.HourCount ah) {
                buckets.add(new HourBucket(ah.getHour().toInstant().toString(), ah.getTotal()));
            } else {
                throw new IllegalStateException("Unexpected hour count row: " + h.getClass());
            }
        }
        StatsResponse value = new StatsResponse(total, byKey, buckets, 60, false);
        statsCache.put(cacheKey, new Cached(value, now.plusSeconds(60)));
        auditQuery(actor, runtime ? "RuntimeLog" : "AccessLog", "QUERY", request, total);
        return value;
    }

    private record Cached(StatsResponse value, Instant expiresAt) {}
    private final Map<String, Cached> statsCache = new ConcurrentHashMap<>();

    private static String statsKey(Scope scope, QueryRequest r) {
        return (scope.orgDomain ? "ORG|" : String.valueOf(scope.scopeSiteKey) + "|")
            + r.type() + "|" + r.from() + "|" + r.to() + "|" + r.levels() + "|" + r.modules()
            + "|" + r.outcomes() + "|" + r.keyword() + "|" + r.traceId();
    }

    // ───────────────────────── 导出 ─────────────────────────

    public record ExportResponse(String token, Instant expiresAt, int count, String format) {}

    /**
     * 导出（FR-LOG-QR-008/009）：单次上限 1 万条、一次性短期 token（默认 30 分钟）、
     * 动作写入 audit_event；SITE 域用户不得导出（§6.2）。
     */
    @Transactional(readOnly = true)
    public ExportResponse export(Authentication authentication, UUID siteId, QueryRequest request, String format) {
        Scope scope = resolveScope(authentication, siteId);
        if (!scope.orgDomain) {
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "当前角色无导出权限");
        }
        String actor = AuditService.currentActor();
        checkRateLimit(actor);
        validateSpan(request);

        Instant to = request.to() == null ? Instant.now() : request.to();
        boolean runtime = !TYPE_ACCESS.equals(request.type());
        int limit = props.getExportLimit();
        long count;
        if (runtime) {
            count = runtimeRepo.search(scope.scopeSiteKey(), request.from(), to,
                normalize(request.levels()), normalize(request.modules()), minLevelRank(request.levels()),
                likePattern(request.keyword()), blankToNull(request.traceId()), null, null,
                PageRequest.of(0, limit + 1)).size();
        } else {
            count = accessRepo.search(scope.scopeSiteKey(), request.from(), to,
                normalize(request.outcomes()), likePattern(request.keyword()), blankToNull(request.traceId()),
                null, null, PageRequest.of(0, limit + 1)).size();
        }
        if (count > limit) {
            throw new BusinessException(ErrorCodes.LOG_EXPORT_LIMIT_EXCEEDED,
                "导出条数超过单次上限 " + limit + "，请缩小筛选范围");
        }
        String token = exportToken(actor, request);
        Instant expiresAt = Instant.now().plus(props.getExportTtl());
        String detail = "filters=" + request + "; count=" + count + "; format=" + format;
        audit.write(actor, "EXPORT", runtime ? "RuntimeLog" : "AccessLog",
            null, siteId, "SUCCESS", Mdc.traceId(), detail);
        return new ExportResponse(token, expiresAt, (int) count, format);
    }

    /** 导出内容（一次性 token 换取；文件首行附生成说明，FR-LOG-QR-009）。 */
    @Transactional(readOnly = true)
    public String exportFile(Authentication authentication, UUID siteId, String token, String format) {
        Scope scope = resolveScope(authentication, siteId);
        if (!scope.orgDomain) {
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "当前角色无导出权限");
        }
        String actor = AuditService.currentActor();
        ExportToken et = exportTokens.remove(token);
        if (et == null || Instant.now().isAfter(et.expiresAt())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "导出链接已失效，请重新导出");
        }
        QueryRequest request = et.request();
        validateSpan(request);
        Instant to = request.to() == null ? Instant.now() : request.to();
        boolean runtime = !TYPE_ACCESS.equals(request.type());
        StringBuilder out = new StringBuilder();
        out.append("# 操作人: ").append(actor)
            .append(" | 生成时间: ").append(Instant.now())
            .append(" | 筛选: type=").append(request.type())
            .append(" from=").append(request.from()).append(" to=").append(to)
            .append(" keyword=").append(request.keyword() == null ? "-" : request.keyword())
            .append(System.lineSeparator());
        if ("json".equalsIgnoreCase(format)) {
            out.append('[');
        }
        boolean first = true;
        if (runtime) {
            for (RuntimeLogEntity e : runtimeRepo.search(scope.scopeSiteKey(), request.from(), to,
                normalize(request.levels()), normalize(request.modules()), minLevelRank(request.levels()),
                likePattern(request.keyword()), blankToNull(request.traceId()), null, null,
                PageRequest.of(0, props.getExportLimit()))) {
                String line = toRuntimeView(e).toString();
                appendLine(out, line, format, first);
                first = false;
            }
        } else {
            for (AccessLogEntity e : accessRepo.search(scope.scopeSiteKey(), request.from(), to,
                normalize(request.outcomes()), likePattern(request.keyword()), blankToNull(request.traceId()),
                null, null, PageRequest.of(0, props.getExportLimit()))) {
                String line = toAccessView(e).toString();
                appendLine(out, line, format, first);
                first = false;
            }
        }
        if ("json".equalsIgnoreCase(format)) {
            out.append(']');
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String line, String format, boolean first) {
        if ("json".equalsIgnoreCase(format)) {
            if (!first) out.append(',');
            out.append(line);
        } else {
            out.append(line.replace("\"", "\"\"")).append(System.lineSeparator());
        }
    }

    private record ExportToken(QueryRequest request, Instant expiresAt) {}
    private final Map<String, ExportToken> exportTokens = new ConcurrentHashMap<>();

    private String exportToken(String actor, QueryRequest request) {
        String raw = actor + "|" + request + "|" + System.nanoTime();
        String token = "logexp-" + sha256(raw).substring(0, 32);
        exportTokens.put(token, new ExportToken(request, Instant.now().plus(props.getExportTtl())));
        return token;
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────── 关联跳转 ─────────────────────────

    /** 同 traceId 关联（FR-LOG-QR-007 / FR-LOG-AC-006）。 */
    @Transactional(readOnly = true)
    public LogPage related(Authentication authentication, UUID siteId, String type, String traceId) {
        resolveScope(authentication, siteId);
        String scopeSiteKey = siteKeyForScope(authentication, siteId);
        boolean runtime = !TYPE_ACCESS.equals(type);
        Pageable limit = PageRequest.of(0, 200);
        List<?> items = runtime
            ? runtimeRepo.findByTraceIdOrderByOccurredAtDesc(traceId, limit).stream()
                .map(this::toRuntimeView).toList()
            : accessRepo.findByTraceIdOrderByOccurredAtDesc(traceId, limit).stream()
                .map(this::toAccessView).toList();
        return new LogPage(items, null, null, false);
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private record Window(long windowStart, java.util.Deque<Long> hits) {}

    private void checkRateLimit(String actor) {
        int limit = Math.max(1, props.getQueryRatePerMinute());
        long now = System.currentTimeMillis();
        Window window = queryWindows.compute(actor, (k, existing) ->
            existing == null || now - existing.windowStart() >= Duration.ofMinutes(1).toMillis()
                ? new Window(now, new java.util.concurrent.ConcurrentLinkedDeque<>()) : existing);
        var hits = window.hits();
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() >= Duration.ofMinutes(1).toMillis()) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                throw new BusinessException(ErrorCodes.LOG_QUERY_RATE_LIMITED, "查询过于频繁，请稍后再试");
            }
            hits.addLast(now);
        }
    }

    private void validateSpan(QueryRequest request) {
        if (request.from() == null) return;
        Instant to = request.to() == null ? Instant.now() : request.to();
        Duration span = Duration.between(request.from(), to);
        if (span.isNegative() || span.compareTo(props.getMaxQuerySpan()) > 0) {
            throw new BusinessException(ErrorCodes.LOG_QUERY_SPAN_EXCEEDED,
                "查询时间跨度超过上限 " + props.getMaxQuerySpan().toDays() + " 天");
        }
    }

    private void auditQuery(String actor, String objectType, String action, QueryRequest request, long count) {
        try {
            String detail = "type=" + request.type() + " from=" + request.from() + " to=" + request.to()
                + " keyword=" + (request.keyword() == null ? "-" : request.keyword())
                + " traceId=" + (request.traceId() == null ? "-" : request.traceId())
                + " count=" + count;
            audit.write(actor, action, objectType, null, null, "SUCCESS", Mdc.traceId(), detail);
        } catch (RuntimeException ignored) {
            // 留痕失败不影响查询（观测旁路）
        }
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        List<String> out = values.stream().filter(v -> v != null && !v.isBlank()).toList();
        return out.isEmpty() ? null : out;
    }

    private static Boolean minLevelRank(List<String> levels) {
        // INFO 及以上默认口径（FR-LOG-QR-001 默认值）：未显式选择 DEBUG/TRACE 时过滤掉低级别
        if (levels == null || levels.isEmpty()) return Boolean.TRUE;
        boolean hasLow = levels.stream().anyMatch(l ->
            RuntimeLogEntity.LEVEL_DEBUG.equalsIgnoreCase(l) || RuntimeLogEntity.LEVEL_TRACE.equalsIgnoreCase(l));
        return hasLow ? null : Boolean.TRUE;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 关键词 → LIKE 模式（去空格 + 小写化 + 通配包裹），作为整体绑定参数参与匹配。
     * <p>不能在 JPQL 里用 {@code concat('%', :kw, '%')} 拼接：PostgreSQL 对 {@code ||}
     * 上下文中无法由列锚定类型的参数会按 bytea 解析，导致 {@code lower(bytea) 不存在}。
     * 以 LIKE 右操作数形式绑定时参数类型由左值（文本列）锚定，H2/PG 行为一致。</p>
     */
    private static String likePattern(String keyword) {
        String kw = blankToNull(keyword);
        return kw == null ? null : "%" + kw.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    private LogPage buildPage(List<?> items, boolean hasMore, boolean runtime) {
        if (!hasMore || items.isEmpty()) {
            return new LogPage(items, null, null, false);
        }
        Object last = items.get(items.size() - 1);
        if (runtime) {
            var v = (RuntimeLogView) last;
            return new LogPage(items, v.occurredAt().toString(), v.id(), true);
        }
        var v = (AccessLogView) last;
        return new LogPage(items, v.occurredAt().toString(), v.id(), true);
    }

    // ───────────────────────── 视图（脱敏兜底，FR-LOG-PM-004）─────────────────────────

    public record RuntimeLogView(String id, String occurredAt, String level, String service, String module,
                                 String node, String thread, String traceId, String siteKey, String message,
                                 String exceptionType, String stack, boolean truncated, Long originalBytes) {}

    public record AccessLogView(String id, String occurredAt, String clientIp, String method, String path,
                                int status, long durationMs, Long respBytes, String identity, String userAgent,
                                String traceId, String siteKey, String outcome, String errorCode) {}

    private RuntimeLogView toRuntimeView(Object entity) {
        RuntimeLogEntity e = (RuntimeLogEntity) entity;
        // 展示侧二次脱敏兜底（FR-LOG-PM-004）：历史数据或文件回读不泄露敏感信息
        return new RuntimeLogView(e.getId().toString(), e.getOccurredAt().toString(), e.getLevel(),
            e.getService(), e.getModule(), e.getNode(), e.getThread(), e.getTraceId(), e.getSiteKey(),
            LogSanitizer.sanitize(e.getMessage()), e.getExceptionType(),
            LogSanitizer.sanitize(e.getStack()), e.isTruncated(), e.getOriginalBytes());
    }

    private AccessLogView toAccessView(Object entity) {
        AccessLogEntity e = (AccessLogEntity) entity;
        return new AccessLogView(e.getId().toString(), e.getOccurredAt().toString(), e.getClientIp(),
            e.getMethod(), LogSanitizer.sanitize(e.getPath()), e.getStatus(), e.getDurationMs(),
            e.getRespBytes(), e.getIdentity(), e.getUserAgent(), e.getTraceId(), e.getSiteKey(),
            e.getOutcome(), e.getErrorCode());
    }

    /** 来源模块聚合（FR-LOG-QR-001 条件⑤，上限 200 项）。 */
    @Transactional(readOnly = true)
    public List<String> modules(Authentication authentication, UUID siteId, Instant from) {
        Scope scope = resolveScope(authentication, siteId);
        return runtimeRepo.distinctModules(scope.scopeSiteKey(),
            from == null ? Instant.now().minus(Duration.ofDays(7)) : from,
            PageRequest.of(0, 200));
    }

    private static final class Mdc {
        static String traceId() {
            return org.slf4j.MDC.get("traceId");
        }
    }
}
