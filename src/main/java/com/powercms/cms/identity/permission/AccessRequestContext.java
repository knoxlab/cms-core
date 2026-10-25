package com.powercms.cms.identity.permission;

import java.time.Instant;

/** 当前请求的客户端地址与时刻。未绑定时条件限制按拒绝处理。 */
public final class AccessRequestContext {

    public record Snapshot(String clientIp, Instant now) {}

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private AccessRequestContext() {}

    public static void bind(String clientIp, Instant now) {
        CURRENT.set(new Snapshot(clientIp, now == null ? Instant.now() : now));
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
