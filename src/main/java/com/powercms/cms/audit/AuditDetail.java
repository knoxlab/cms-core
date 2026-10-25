package com.powercms.cms.audit;

/**
 * DEF-M12-014：请求线程内的审计补充说明（detail）载体。
 *
 * <p>业务代码在处理过程中（如状态推进前）写入「状态 DRAFT → IN_REVIEW」这类前后值说明，
 * {@link com.powercms.cms.platform.web.ManagementAuditInterceptor} 在请求收尾时取出并随
 * {@code audit_event.detail} 一并落库。用 ThreadLocal 而非透传参数，是为了不改动
 * {@code AuditService#write} 的既有调用面（拦截器拿到的是 HandlerMethod，业务上下文早已散落各处）。</p>
 *
 * <p>取值即清除（{@link #getAndClear()}），避免线程池复用导致 detail 串写到下一个请求。</p>
 */
public final class AuditDetail {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private AuditDetail() {
    }

    /** 挂载本请求的审计说明；传 null/空白视为清除。 */
    public static void set(String detail) {
        if (detail == null || detail.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(detail);
        }
    }

    /** 读取当前线程的审计说明（不清除）。 */
    public static String get() {
        return HOLDER.get();
    }

    /** 读取并清除，供拦截器在请求收尾时一次性取用。 */
    public static String getAndClear() {
        String detail = HOLDER.get();
        HOLDER.remove();
        return detail;
    }

    /** 显式清除（异常兜底路径）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
