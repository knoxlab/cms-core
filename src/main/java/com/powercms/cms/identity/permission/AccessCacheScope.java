package com.powercms.cms.identity.permission;

/** 本次请求的成功响应能否进入共享缓存。条件策略命中时关闭。 */
public final class AccessCacheScope {

    private static final ThreadLocal<Boolean> SHARED = ThreadLocal.withInitial(() -> Boolean.TRUE);

    private AccessCacheScope() {}

    public static void reset() {
        SHARED.set(Boolean.TRUE);
    }

    public static void denyShared() {
        SHARED.set(Boolean.FALSE);
    }

    public static boolean shared() {
        return SHARED.get();
    }

    public static void clear() {
        SHARED.remove();
    }
}
