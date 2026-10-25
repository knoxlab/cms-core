package com.powercms.cms.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.publish.entity.ReleaseUrlRewriteEntity;
import com.powercms.cms.publish.repo.ReleaseMediaVersionRepository;
import com.powercms.cms.publish.repo.ReleaseUrlRewriteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseStaticAssetExporterTest {
    @Mock ReleaseMediaVersionRepository mediaRepo;
    @Mock ReleaseUrlRewriteRepository rewriteRepo;

    @Test void exportsExactRedirectsAndSkipsRegex(@TempDir Path out) throws Exception {
        UUID releaseId = UUID.randomUUID();
        when(mediaRepo.findByReleaseIdOrderByOutputPathAsc(releaseId)).thenReturn(List.of());
        var exact = rule(releaseId, "^\\Q/old\\E$", "/new", 10, "move");
        var regex = rule(releaseId, "^/blog/(.*)$", "/posts/$1", 5, "blog");
        when(rewriteRepo.findByReleaseIdOrderByPriorityDescNameAsc(releaseId)).thenReturn(List.of(exact, regex));

        var exporter = new ReleaseStaticAssetExporter(mediaRepo, rewriteRepo, new ObjectMapper(), out.toString(), false);
        var result = exporter.export(releaseId, out);

        assertThat(result.redirectsExported()).isEqualTo(1);
        assertThat(result.redirectsSkipped()).isEqualTo(1);
        assertThat(Files.readString(out.resolve("_redirects"))).isEqualTo("/old /new 301\n");
        assertThat(Files.readString(out.resolve("nginx.redirects"))).contains("location = /old");
        assertThat(Files.readString(out.resolve("redirects-manifest.json"))).contains("non-exact regex");
    }

    @Test void outputPathIsStableAndSafe() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertThat(ReleaseStaticAssetExporter.outputPath(id, "../evil.png"))
            .isEqualTo("media/11111111-1111-1111-1111-111111111111/evil.png");
        assertThat(ReleaseStaticAssetExporter.outputPath(id, "a/b/photo.webp"))
            .isEqualTo("media/11111111-1111-1111-1111-111111111111/photo.webp");
    }

    private static ReleaseUrlRewriteEntity rule(UUID releaseId, String pattern, String replacement, int priority, String name) {
        var r = new ReleaseUrlRewriteEntity();
        r.setReleaseId(releaseId);
        r.setRuleId(UUID.randomUUID());
        r.setName(name);
        r.setPattern(pattern);
        r.setReplacement(replacement);
        r.setPriority(priority);
        return r;
    }
}
