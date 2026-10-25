package com.powercms.cms.publish;

import com.powercms.cms.publish.entity.ReleasePageEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncrementalReleaseDiffTest {
    @Test void classifiesAddedChangedRemovedUnchanged() {
        var baseline = List.of(page("/a", "d1", "a"), page("/b", "d2", "b"), page("/c", "d3", "c"));
        var target = List.of(page("/a", "d1", "a"), page("/b", "d2-new", "b2"), page("/d", "d4", "d"));
        var diff = IncrementalReleaseDiff.compare(baseline, target);
        assertThat(diff.unchanged()).extracting(IncrementalReleaseDiff.PageRef::routePath).containsExactly("/a");
        assertThat(diff.changed()).extracting(IncrementalReleaseDiff.PageRef::routePath).containsExactly("/b");
        assertThat(diff.removed()).extracting(IncrementalReleaseDiff.PageRef::routePath).containsExactly("/c");
        assertThat(diff.added()).extracting(IncrementalReleaseDiff.PageRef::routePath).containsExactly("/d");
    }

    @Test void relativeHtmlPathUsesIndexConvention() {
        assertThat(IncrementalReleaseDiff.relativeHtmlPath("/")).isEqualTo("index.html");
        assertThat(IncrementalReleaseDiff.relativeHtmlPath("/about")).isEqualTo("about/index.html");
    }

    @Test void deleteUsesSafePathsOnly(@TempDir Path root) throws Exception {
        Path nested = Files.createDirectories(root.resolve("about"));
        Path file = nested.resolve("index.html");
        Files.writeString(file, "x");
        Path target = BuildWorker.safe(root, IncrementalReleaseDiff.relativeHtmlPath("/about"));
        assertThat(target).isEqualTo(file);
        Files.deleteIfExists(target);
        assertThat(Files.exists(file)).isFalse();
        assertThatThrownBy(() -> BuildWorker.safe(root, "../secret")).isInstanceOf(SecurityException.class);
    }

    private static ReleasePageEntity page(String route, String digest, String html) {
        var p = new ReleasePageEntity();
        p.setReleaseId(UUID.randomUUID());
        p.setPageId(UUID.randomUUID());
        p.setRoutePath(route);
        p.setInputDigest(digest);
        p.setHtmlContent(html);
        p.setContentVersion(1);
        return p;
    }
}
