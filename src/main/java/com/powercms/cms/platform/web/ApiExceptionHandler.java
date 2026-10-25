package com.powercms.cms.platform.web;

import com.powercms.cms.platform.i18n.Messages;
import com.powercms.common.error.ApiError;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 统一错误响应（规格书 11.1）：code + message + traceId + 字段级 details。
 * 5xx 不返回堆栈和服务器信息。
 * 所有对外 message 均经 {@link Messages} 按请求语言本地化（FR-I18N-002）：
 * BusinessException 优先按其**稳定消息键 + 参数**翻译（语序由目标语言决定），
 * 未携带键时按"原文即键"翻译；框架固定消息按稳定键翻译。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Messages messages;

    public ApiExceptionHandler(MessageSource messageSource) {
        this.messages = new Messages(messageSource);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        HttpStatus status = mapStatus(ex.getCode());
        return ResponseEntity.status(status)
            .body(new ApiError(ex.getCode(), resolveMessage(ex), currentTraceId(), ex.getDetails()));
    }

    /**
     * 业务异常文案（OBS-MS-04）：带稳定消息键的按**键 + 参数**本地化（语序与空格由目标语言
     * 文案决定，不受中文切分影响）；其余仍按「中文原文即键」翻译。
     */
    private String resolveMessage(BusinessException ex) {
        String key = ex.getMessageKey();
        return key != null ? messages.get(key, ex.getMessageArgs()) : messages.byText(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ApiError.FieldError(fe.getField(),
                messages.byText(String.valueOf(fe.getDefaultMessage()))))
            .toList();
        return ResponseEntity.badRequest()
            .body(new ApiError(ErrorCodes.VALIDATION_FAILED, messages.get("error.validation.failed"),
                currentTraceId(), details));
    }

    /**
     * 请求参数/路径变量类型不匹配（如 `GET /media/knox/bindings/not-a-uuid` 的 siteId 不是 UUID）
     * 与 JSON 体不可解析同口径返回 400。此前 `MethodArgumentTypeMismatchException` 落到兜底处理器
     * 被压成 500（DEF-MS-03），把客户端拼错 ID 误报成服务端故障，并污染 ERROR 级日志。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
            .body(ApiError.of(ErrorCodes.BAD_REQUEST, messages.get("error.bad.request"), currentTraceId()));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ApiError> handleAccessDenied(Exception ex) {
        BusinessException business = findBusiness(ex);
        if (business != null) {
            return handleBusiness(business);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiError.of(ErrorCodes.PERMISSION_DENIED, messages.get("error.permission.denied"),
                currentTraceId()));
    }

    /**
     * 资源不存在一律 404。
     *
     * <p>OBS-PKG-03：静态资源（{@code /assets/**}）在服务运行期被删除时，资源解析链仍持有该资源的句柄，
     * {@code AbstractFileResolvingResource.lastModified} 会抛
     * {@code FileNotFoundException: URL [file:…] cannot be resolved in the file system for checking its last-modified timestamp}。
     * 此前该异常落到兜底处理器，被当作未处理异常记 ERROR 并返回 500（客户端反复重试也无意义），
     * 故这里与 {@link NoResourceFoundException} 同口径映射为 404（重启或重新构建产物即自然恢复）。</p>
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class,
        FileNotFoundException.class, NoSuchFileException.class})
    ResponseEntity<ApiError> handleMissingResource(Exception ex) {
        log.debug("资源不存在: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError.of(ErrorCodes.NOT_FOUND, messages.get("error.not.found"), currentTraceId()));
    }

    /**
     * OBS-01：路径存在但方法不被支持（如 {@code PATCH /api/v1/contents/{id}}）。
     * <p>
     * 此前该异常落到兜底处理器，被当作未处理异常记 ERROR 并返回 500，掩盖了 405 语义。
     * 这里显式映射为 405，并按 RFC 9110 回填 {@code Allow} 头（客户端可据此自愈）。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.debug("请求方法不被支持: {} allowed={}", ex.getMethod(), ex.getSupportedHttpMethods());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(ApiError.of(ErrorCodes.METHOD_NOT_ALLOWED,
            messages.get("error.method.not.allowed"), currentTraceId()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.debug("请求体媒体类型不被支持: {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ApiError.of(ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                messages.get("error.media.type.unsupported"), currentTraceId()));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.debug("Accept 无法满足: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
            .body(ApiError.of(ErrorCodes.NOT_ACCEPTABLE,
                messages.get("error.not.acceptable"), currentTraceId()));
    }

    /**
     * OBS-01 同类泛化：任何实现 {@link ErrorResponse} 且自带状态码的 Spring 异常
     * （典型为 {@code ResponseStatusException}）不再落入兜底处理器被压成 500，
     * 而是按声明的状态码 + 语义化错误码对外；5xx 仍按未处理异常记 ERROR。
     */
    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ApiError> handleErrorResponse(ErrorResponseException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = codeForStatus(status);
        String traceId = currentTraceId();
        if (status.is5xxServerError()) {
            log.error("状态异常(5xx) traceId={} status={}", traceId, status.value(), ex);
        } else {
            log.debug("状态异常 traceId={} status={}", traceId, status.value());
        }
        return ResponseEntity.status(status)
            .body(ApiError.of(code, messages.get(messageKeyForStatus(status)), traceId));
    }

    private static String codeForStatus(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> ErrorCodes.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCodes.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCodes.UNSUPPORTED_MEDIA_TYPE;
            case NOT_ACCEPTABLE -> ErrorCodes.NOT_ACCEPTABLE;
            case BAD_REQUEST -> ErrorCodes.BAD_REQUEST;
            case UNAUTHORIZED -> ErrorCodes.UNAUTHENTICATED;
            case FORBIDDEN -> ErrorCodes.PERMISSION_DENIED;
            case CONFLICT -> ErrorCodes.CONFLICT;
            case TOO_MANY_REQUESTS -> ErrorCodes.RATE_LIMITED;
            case PAYLOAD_TOO_LARGE -> ErrorCodes.MEDIA_FILE_TOO_LARGE;
            case PAYMENT_REQUIRED -> ErrorCodes.LICENSE_EXPIRED;
            default -> ErrorCodes.INTERNAL_ERROR;
        };
    }

    private static String messageKeyForStatus(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "error.not.found";
            case METHOD_NOT_ALLOWED -> "error.method.not.allowed";
            case UNSUPPORTED_MEDIA_TYPE -> "error.media.type.unsupported";
            case NOT_ACCEPTABLE -> "error.not.acceptable";
            case BAD_REQUEST, PAYLOAD_TOO_LARGE -> "error.bad.request";
            case UNAUTHORIZED -> "error.auth.unauthenticated";
            case FORBIDDEN -> "error.permission.denied";
            case CONFLICT -> "error.data.conflict";
            case TOO_MANY_REQUESTS -> "error.rate.limited";
            case PAYMENT_REQUIRED -> "error.license.expired";
            default -> "error.internal";
        };
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause() != null
            ? ex.getMostSpecificCause().getMessage()
            : ex.getMessage();
        if (message != null && message.contains("FOREIGN KEY") && message.contains("site_id")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCodes.NOT_FOUND,
                    messages.get("error.site.missing.reselect"), currentTraceId()));
        }
        log.warn("数据完整性约束失败 traceId={}", currentTraceId(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of(ErrorCodes.CONFLICT, messages.get("error.data.conflict"), currentTraceId()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("上传超过大小限制 traceId={}", currentTraceId());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ApiError.of(ErrorCodes.MEDIA_FILE_TOO_LARGE,
                messages.get("error.media.file.too.large"), currentTraceId()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // 客户端提前断开（video 元素取完首帧数据即 abort）属正常行为，降级为 DEBUG 且不写错误响应体
        if (ex instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException
            || ex instanceof org.apache.catalina.connector.ClientAbortException) {
            log.debug("client aborted connection traceId={}", currentTraceId());
            return null;
        }
        String traceId = currentTraceId();
        log.error("未处理异常 traceId={}", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError.of(ErrorCodes.INTERNAL_ERROR, messages.get("error.internal"), traceId));
    }

    private static HttpStatus mapStatus(String code) {
        return switch (code) {
            case ErrorCodes.VALIDATION_FAILED, ErrorCodes.BAD_REQUEST, ErrorCodes.FIELD_CODE_INVALID, ErrorCodes.FIELD_TYPE_UNSUPPORTED, ErrorCodes.NESTING_DEPTH_EXCEEDED, ErrorCodes.ENUM_OPTIONS_EMPTY, ErrorCodes.REF_CONTENT_TYPE_NOT_FOUND, ErrorCodes.SECTION_REQUIRED, ErrorCodes.INHERIT_CYCLE_DETECTED, ErrorCodes.PARENT_TYPE_NOT_FOUND, ErrorCodes.LIFECYCLE_SCRIPT_INVALID, ErrorCodes.VARIABLE_NAME_RESERVED, ErrorCodes.VARIABLE_NAME_INVALID_SUFFIX, ErrorCodes.CONFIG_REGEX_INVALID, ErrorCodes.QUICK_CREATE_DESTINATION_REQUIRED, ErrorCodes.RELATION_CARDINALITY_INVALID, ErrorCodes.RELATION_TYPE_INVALID, ErrorCodes.SYSTEM_FIELD_LOCKED, ErrorCodes.FIELD_VARIABLE_KEY_INVALID, ErrorCodes.INDEX_MAPPING_INVALID, ErrorCodes.CUSTOM_CODE_COMPILE_ERROR, ErrorCodes.CUSTOM_CODE_EXECUTION_ERROR, ErrorCodes.BLOCK_CHAR_LIMIT_EXCEEDED, ErrorCodes.TEMPLATE_RENDER_ERROR, ErrorCodes.ROLE_SCOPE_INVALID, ErrorCodes.WORKFLOW_COMMENT_REQUIRED, ErrorCodes.SCHEDULE_INVALID, ErrorCodes.MEDIA_TYPE_NOT_FOUND, ErrorCodes.MEDIA_ACCEPT_INVALID, ErrorCodes.MEDIA_ENV_NOT_CONFIGURED, ErrorCodes.INTERACTION_SENSITIVE_HIT, ErrorCodes.INTERACTION_ROOM_CLOSED, ErrorCodes.INTERACTION_ROOM_MUTED, ErrorCodes.INTERACTION_AD_CAP_REACHED, ErrorCodes.AI_CAPABILITY_NOT_SUPPORTED, ErrorCodes.AI_CREDENTIAL_INVALID, ErrorCodes.AI_SAFETY_BLOCKED,
                ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID, ErrorCodes.SITE_PACKAGE_STRUCTURE_INVALID,
                ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, ErrorCodes.SITE_PACKAGE_VERSION_INCOMPATIBLE,
                ErrorCodes.SITE_PACKAGE_CHECKSUM_MISMATCH, ErrorCodes.SITE_PACKAGE_SIGNATURE_REQUIRED,
                ErrorCodes.SITE_PACKAGE_SIGNATURE_INVALID, ErrorCodes.SITE_PACKAGE_EXECUTABLE_FORBIDDEN,
                ErrorCodes.SITE_PACKAGE_MODEL_INVALID, ErrorCodes.ACCESS_POLICY_INVALID -> HttpStatus.BAD_REQUEST;
            // FR-SEARCH-004 / AC-CM-009：字段级排序/过滤按内容模型属性做白名单校验，
            // 未开对应属性的字段一律 400 明确拒绝（不得静默忽略，否则调用方误判排序已生效）
            case ErrorCodes.SEARCH_SORT_UNSUPPORTED, ErrorCodes.SEARCH_FILTER_UNSUPPORTED,
                ErrorCodes.SEARCH_CURSOR_INVALID -> HttpStatus.BAD_REQUEST;
            case ErrorCodes.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case ErrorCodes.PERMISSION_DENIED, ErrorCodes.FORBIDDEN, ErrorCodes.TIME_MACHINE_READ_ONLY, ErrorCodes.SANDBOX_VIOLATION, ErrorCodes.WORKFLOW_PERMISSION_DENIED, ErrorCodes.DELEGATION_PERMISSION_DENIED, ErrorCodes.SELF_APPROVAL_FORBIDDEN, ErrorCodes.WORKFLOW_ACTION_NOT_AVAILABLE, ErrorCodes.TOKEN_SCOPE_DENIED, ErrorCodes.BACKEND_ACCESS_DENIED, ErrorCodes.NO_BACKEND_LAYOUT, ErrorCodes.INTERACTION_GUEST_FORBIDDEN, ErrorCodes.INTERACTION_REVIEW_FORBIDDEN, ErrorCodes.INTERACTION_TOKEN_INVALID, ErrorCodes.INTERACTION_CAPTCHA_REQUIRED, ErrorCodes.AI_DISABLED, ErrorCodes.AI_APPROVAL_REQUIRED, ErrorCodes.AI_INJECTION_BLOCKED, ErrorCodes.APPROVAL_TICKET_REQUIRED, ErrorCodes.APPROVAL_TICKET_INVALID, ErrorCodes.AGENT_REVIEW_EXECUTE_DISABLED, ErrorCodes.HIGH_RISK_SAME_USER_FORBIDDEN, ErrorCodes.ACCESS_WINDOW_DENIED, ErrorCodes.ACCESS_IP_DENIED, ErrorCodes.DOWNLOAD_DISABLED, ErrorCodes.DOWNLOAD_NOT_GRANTED -> HttpStatus.FORBIDDEN;
            case ErrorCodes.NOT_FOUND, ErrorCodes.CONTENT_TYPE_NOT_FOUND, ErrorCodes.RELATION_NOT_FOUND, ErrorCodes.FIELD_NOT_FOUND, ErrorCodes.INTERACTION_MESSAGE_NOT_FOUND, ErrorCodes.INTERACTION_DEFINITION_NOT_PUBLISHED, ErrorCodes.AI_PROMPT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case ErrorCodes.UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case ErrorCodes.NOT_ACCEPTABLE -> HttpStatus.NOT_ACCEPTABLE;
            case ErrorCodes.CONFLICT, ErrorCodes.CONTENT_VERSION_CONFLICT, ErrorCodes.CONTENT_TYPE_CODE_DUPLICATE, ErrorCodes.FIELD_CODE_DUPLICATE, ErrorCodes.CONTENT_TYPE_PUBLISHED_READONLY, ErrorCodes.CONTENT_TYPE_REFERENCED, ErrorCodes.TEMPLATE_TYPE_IMMUTABLE, ErrorCodes.OBJECT_TYPE_IMMUTABLE, ErrorCodes.RELATION_NAME_DUPLICATE, ErrorCodes.RELATION_HAS_INSTANCES, ErrorCodes.FIELD_VALUE_DUPLICATE, ErrorCodes.LAST_ADMIN_FORBIDDEN, ErrorCodes.BUILTIN_ROLE_LOCKED, ErrorCodes.INVALID_TASK_STATE, ErrorCodes.ALREADY_IN_REVIEW, ErrorCodes.BACKEND_ROLE_FORBIDDEN, ErrorCodes.PERMISSION_INHERIT_CONFLICT, ErrorCodes.USER_REFERENCE_CONFLICT, ErrorCodes.INTERACTION_DUPLICATE_SUBMISSION, ErrorCodes.INTERACTION_DUPLICATE_LIKE, ErrorCodes.INTERACTION_DUPLICATE_REPORT, ErrorCodes.HIGH_RISK_CONFIRMATION_REQUIRED, ErrorCodes.HIGH_RISK_CONFIRMATION_INVALID,
                ErrorCodes.SITE_PACKAGE_CONFLICT_BLOCKED, ErrorCodes.SITE_PACKAGE_CONFIRMATION_REQUIRED,
                ErrorCodes.SITE_PACKAGE_CONSENT_INVALID, ErrorCodes.SITE_DELETE_BLOCKED -> HttpStatus.CONFLICT;
            case ErrorCodes.SITE_FROZEN -> HttpStatus.LOCKED;
            case ErrorCodes.RATE_LIMITED, ErrorCodes.INTERACTION_RATE_LIMITED, ErrorCodes.INTERACTION_SLOW_MODE, ErrorCodes.AI_QUOTA_EXCEEDED, ErrorCodes.AI_RATE_LIMITED, ErrorCodes.MCP_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            // FR-LOG-QR-004/008：日志查询限流 429、跨度超限与导出超限 400（§12.1 日志专属错误码）
            case ErrorCodes.LOG_QUERY_SPAN_EXCEEDED, ErrorCodes.LOG_EXPORT_LIMIT_EXCEEDED -> HttpStatus.BAD_REQUEST;
            case ErrorCodes.LOG_QUERY_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case ErrorCodes.INDEX_RELEASE_MISMATCH -> HttpStatus.CONFLICT;
            case ErrorCodes.MEDIA_UPSTREAM_UNAVAILABLE, ErrorCodes.AI_MODEL_NOT_CONFIGURED, ErrorCodes.AI_MODEL_DISABLED, ErrorCodes.AI_UPSTREAM_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
            // FR-AI-KB-025：向量形态不一致（查询维度 ≠ 索引维度）→ 503。
            // 不给 500：那会被当成缺陷上报，而这是**可运维的配置问题**（重建索引或
            // 恢复原模型配置即可），语义上属于"依赖此刻不可用"。
            // 也不给 400：请求本身没有错——换个查询词同样会被拒。
            case ErrorCodes.AI_EMBEDDING_SHAPE_MISMATCH -> HttpStatus.SERVICE_UNAVAILABLE;
            case ErrorCodes.MEDIA_FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case ErrorCodes.KNOX_ENDPOINT_NOT_FOUND, ErrorCodes.MEDIA_QUOTA_EXCEEDED -> HttpStatus.CONFLICT;
            // 授权（docs/license-and-offline-subscription-design.md §7）
            case ErrorCodes.LICENSE_CAPABILITY_DISABLED,
                ErrorCodes.LICENSE_NODE_LIMIT_EXCEEDED,
                ErrorCodes.LICENSE_FINGERPRINT_MISMATCH -> HttpStatus.FORBIDDEN;
            case ErrorCodes.LICENSE_QUOTA_EXCEEDED -> HttpStatus.CONFLICT;
            // 到期/只读/吊销/时钟回拨：402 明确表达「付款/续期即可恢复」，而不是权限不足
            case ErrorCodes.LICENSE_EXPIRED, ErrorCodes.LICENSE_READ_ONLY,
                ErrorCodes.LICENSE_REVOKED, ErrorCodes.LICENSE_CLOCK_REGRESSION ->
                HttpStatus.PAYMENT_REQUIRED;
            case ErrorCodes.LICENSE_MISSING, ErrorCodes.LICENSE_INVALID,
                ErrorCodes.LICENSE_SEQ_REGRESSED, ErrorCodes.LICENSE_UPLOAD_REJECTED,
                ErrorCodes.LICENSE_PUBLIC_KEY_MISSING,
                ErrorCodes.LICENSE_SHORT_CODE_INVALID,
                ErrorCodes.LICENSE_UNSUPPORTED_ALGORITHM -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }

    private static BusinessException findBusiness(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof BusinessException business) return business;
            current = current.getCause();
        }
        return null;
    }
}
