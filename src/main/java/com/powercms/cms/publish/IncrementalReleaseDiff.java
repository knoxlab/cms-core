package com.powercms.cms.publish;

import com.powercms.cms.publish.entity.ReleasePageEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Classifies page-level differences between two frozen releases for incremental SSG. */
public final class IncrementalReleaseDiff {
    private IncrementalReleaseDiff() {}

    public record PageRef(String routePath, String inputDigest, String htmlContent) {}
    public record Result(
        List<PageRef> added,
        List<PageRef> changed,
        List<PageRef> removed,
        List<PageRef> unchanged
    ) {
        public int addedCount() { return added.size(); }
        public int changedCount() { return changed.size(); }
        public int removedCount() { return removed.size(); }
        public int unchangedCount() { return unchanged.size(); }
    }

    public static Result compare(List<ReleasePageEntity> baseline, List<ReleasePageEntity> target) {
        Map<String, ReleasePageEntity> baseByRoute = index(baseline);
        Map<String, ReleasePageEntity> targetByRoute = index(target);
        List<PageRef> added = new ArrayList<>();
        List<PageRef> changed = new ArrayList<>();
        List<PageRef> removed = new ArrayList<>();
        List<PageRef> unchanged = new ArrayList<>();
        for (var entry : targetByRoute.entrySet()) {
            ReleasePageEntity page = entry.getValue();
            ReleasePageEntity prior = baseByRoute.get(entry.getKey());
            PageRef ref = ref(page);
            if (prior == null) added.add(ref);
            else if (!Objects.equals(prior.getInputDigest(), page.getInputDigest())) changed.add(ref);
            else unchanged.add(ref);
        }
        for (var entry : baseByRoute.entrySet()) {
            if (!targetByRoute.containsKey(entry.getKey())) removed.add(ref(entry.getValue()));
        }
        return new Result(List.copyOf(added), List.copyOf(changed), List.copyOf(removed), List.copyOf(unchanged));
    }

    public static String relativeHtmlPath(String route) {
        if (route == null || route.isBlank() || "/".equals(route)) return "index.html";
        String normalized = route.startsWith("/") ? route.substring(1) : route;
        return normalized + "/index.html";
    }

    private static Map<String, ReleasePageEntity> index(List<ReleasePageEntity> pages) {
        Map<String, ReleasePageEntity> out = new LinkedHashMap<>();
        if (pages == null) return out;
        for (ReleasePageEntity page : pages) {
            if (page.getRoutePath() == null || page.getRoutePath().isBlank()) continue;
            out.put(page.getRoutePath(), page);
        }
        return out;
    }

    private static PageRef ref(ReleasePageEntity page) {
        return new PageRef(page.getRoutePath(), page.getInputDigest(), page.getHtmlContent());
    }
}
