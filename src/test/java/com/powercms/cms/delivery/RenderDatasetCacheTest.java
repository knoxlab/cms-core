package com.powercms.cms.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RenderDatasetCacheTest {
    private RenderDatasetCache cache;

    @BeforeEach
    void setUp() {
        cache = new RenderDatasetCache(3);
    }

    @Test
    void cachesWithLruEvictionAndHitStats() {
        AtomicInteger loads = new AtomicInteger();
        cache.getOrLoad(RenderDatasetCache.Region.TEMPLATE, "a", true, () -> { loads.incrementAndGet(); return "A"; });
        cache.getOrLoad(RenderDatasetCache.Region.TEMPLATE, "b", true, () -> { loads.incrementAndGet(); return "B"; });
        cache.getOrLoad(RenderDatasetCache.Region.TEMPLATE, "c", true, () -> { loads.incrementAndGet(); return "C"; });
        assertThat(cache.getOrLoad(RenderDatasetCache.Region.TEMPLATE, "a", true, () -> { loads.incrementAndGet(); return "A2"; }))
            .isEqualTo("A");
        cache.getOrLoad(RenderDatasetCache.Region.TEMPLATE, "d", true, () -> { loads.incrementAndGet(); return "D"; });

        assertThat(loads.get()).isEqualTo(4);
        assertThat(cache.stats().hits()).isEqualTo(1);
        assertThat(cache.stats().misses()).isEqualTo(4);
        assertThat(cache.stats().sizes().get("TEMPLATE")).isEqualTo(3);
        assertThat(cache.getOrLoad(RenderDatasetCache.Region.TEMPLATE, "b", true, () -> "B2")).isEqualTo("B2");
    }

    @Test
    void previewBypassesCache() {
        AtomicInteger loads = new AtomicInteger();
        cache.getOrLoad(RenderDatasetCache.Region.CONTENT, "1:1", false, () -> { loads.incrementAndGet(); return "v1"; });
        cache.getOrLoad(RenderDatasetCache.Region.CONTENT, "1:1", false, () -> { loads.incrementAndGet(); return "v2"; });
        assertThat(loads.get()).isEqualTo(2);
        assertThat(cache.stats().bypasses()).isEqualTo(2);
        assertThat(cache.stats().hits()).isZero();
        assertThat(cache.stats().sizes().get("CONTENT")).isZero();
    }

    @Test
    void invalidateAndClearResetEntries() {
        cache.getOrLoad(RenderDatasetCache.Region.CONTAINER, "c1", true, () -> "cfg");
        cache.invalidate(RenderDatasetCache.Region.CONTAINER, "c1");
        AtomicInteger loads = new AtomicInteger();
        cache.getOrLoad(RenderDatasetCache.Region.CONTAINER, "c1", true, () -> { loads.incrementAndGet(); return "cfg2"; });
        assertThat(loads.get()).isEqualTo(1);
        cache.clearAll();
        assertThat(cache.stats().hits()).isZero();
        assertThat(cache.stats().sizes().get("CONTAINER")).isZero();
    }
}
