package com.powercms.cms.platform.gateway;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 解耦 Micrometer 依赖：注册表缺失（如纯单测）时退化为进程内计数器，静默跳过指标上报。
 */
public final class RegistryHolder {

    private static volatile Supplier<MeterRegistry> registry = () -> null;
    private static final AtomicLong LIMITED_FALLBACK = new AtomicLong();

    private RegistryHolder() {}

    static void incrementLimited() {
        MeterRegistry meterRegistry = registry.get();
        if (meterRegistry == null) {
            LIMITED_FALLBACK.incrementAndGet();
            return;
        }
        io.micrometer.core.instrument.Counter.builder(RateLimitFilter.METRIC_LIMITED)
            .description("公开 API 限流触发次数")
            .register(meterRegistry)
            .increment();
    }

    public static long limitedFallbackCount() {
        return LIMITED_FALLBACK.get();
    }

    public static void set(Supplier<MeterRegistry> supplier) {
        registry = supplier == null ? () -> null : supplier;
    }
}
