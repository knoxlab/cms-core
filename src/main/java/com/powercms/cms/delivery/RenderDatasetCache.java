package com.powercms.cms.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
public class RenderDatasetCache {
    public enum Region { SYSTEM, APPLICATION, TEMPLATE, THEME, CONTENT, CONTAINER, SCRIPT }

    private final int maxEntries;
    private final EnumMap<Region, RegionStore> stores = new EnumMap<>(Region.class);
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong bypasses = new AtomicLong();

    public RenderDatasetCache(@Value("${powercms.render.cache.max-entries-per-region:512}") int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        for (Region region : Region.values()) stores.put(region, new RegionStore(this.maxEntries));
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(Region region, String key, boolean useCache, Supplier<T> loader) {
        if (!useCache) {
            bypasses.incrementAndGet();
            return loader.get();
        }
        RegionStore store = require(region);
        Object cached = store.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return (T) cached;
        }
        misses.incrementAndGet();
        T loaded = loader.get();
        if (loaded != null) store.put(key, loaded);
        return loaded;
    }

    public void invalidate(Region region, String key) {
        if (region != null && key != null) require(region).remove(key);
    }

    public void invalidatePrefix(Region region, String prefix) {
        if (region != null && prefix != null) require(region).removePrefix(prefix);
    }

    public void clear(Region region) {
        if (region != null) require(region).clear();
    }

    public void clearAll() {
        for (Region region : Region.values()) stores.get(region).clear();
        hits.set(0); misses.set(0); bypasses.set(0);
    }

    public Stats stats() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (Region region : Region.values()) sizes.put(region.name(), stores.get(region).size());
        long hitCount = hits.get(), missCount = misses.get();
        return new Stats(hitCount, missCount, bypasses.get(),
            hitCount + missCount == 0 ? 0 : (double) hitCount / (hitCount + missCount), maxEntries, sizes);
    }

    private RegionStore require(Region region) {
        if (region == null) throw new IllegalArgumentException("cache region is required");
        return stores.get(region);
    }

    private static final class RegionStore {
        private final int maxEntries;
        private final LinkedHashMap<String, Object> values = new LinkedHashMap<>(16, 0.75f, true);
        private RegionStore(int maxEntries) { this.maxEntries = maxEntries; }
        private synchronized Object get(String key) { return values.get(key); }
        private synchronized void put(String key, Object value) {
            values.put(key, value);
            while (values.size() > maxEntries) values.remove(values.keySet().iterator().next());
        }
        private synchronized void remove(String key) { values.remove(key); }
        private synchronized void removePrefix(String prefix) { values.keySet().removeIf(key -> key.startsWith(prefix)); }
        private synchronized void clear() { values.clear(); }
        private synchronized int size() { return values.size(); }
    }

    public record Stats(long hits, long misses, long bypasses, double hitRate,
                        int maxEntriesPerRegion, Map<String, Integer> sizes) {}
}
