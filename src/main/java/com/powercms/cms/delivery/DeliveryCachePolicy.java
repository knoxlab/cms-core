package com.powercms.cms.delivery;

/**
 * 交付缓存口径（FR-ACCESS-012）。
 * 公开缓存只给匿名公开档，最长保留 {@link #DEFAULT_MAX_AGE_SECONDS} 秒，不再使用更长的 stale-while-revalidate。
 */
public final class DeliveryCachePolicy {

    public static final int DEFAULT_MAX_AGE_SECONDS = 60;

    private DeliveryCachePolicy() {}

    public static String anonymousPublic() {
        return anonymousPublic(DEFAULT_MAX_AGE_SECONDS);
    }

    public static String anonymousPublic(int maxAgeSeconds) {
        int age = Math.max(0, maxAgeSeconds);
        return "public, max-age=" + age;
    }

    public static String memberPrivate() {
        return memberPrivate(DEFAULT_MAX_AGE_SECONDS);
    }

    public static String memberPrivate(int maxAgeSeconds) {
        int age = Math.max(0, maxAgeSeconds);
        return "private, max-age=" + age;
    }

    public static String unshared() {
        return "private, no-store";
    }

    public static String preview() {
        return "no-store";
    }
}
