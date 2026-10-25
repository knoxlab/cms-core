package com.powercms.cms.publish;

import com.powercms.cms.publish.entity.BuildJobEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BuildWorkerPathTest {
    @Test void acceptsRelativeChild(){Path r=Path.of("target/work").toAbsolutePath().normalize();assertEquals(r.resolve("src/app.js"),BuildWorker.safe(r,"src/app.js"));}
    @Test void rejectsTraversal(){Path r=Path.of("target/work").toAbsolutePath();assertThrows(SecurityException.class,()->BuildWorker.safe(r,"../secret"));}
    @Test void rejectsAbsolute(){Path r=Path.of("target/work").toAbsolutePath();assertThrows(SecurityException.class,()->BuildWorker.safe(r,"C:\\secret"));}

    @Test void previewOutputIsImmutableAndRevisionScoped() {
        Path root = Path.of("target/builds").toAbsolutePath().normalize();
        BuildJobEntity job = job(BuildJobEntity.JOB_PREVIEW, 7);
        assertEquals(root.resolve("previews").resolve(job.getTemplateProjectId().toString()).resolve("7").resolve(job.getId().toString()),
            BuildWorker.outputPath(root, job));
    }

    @Test void releaseOutputUsesArtifactBoundary() {
        Path root = Path.of("target/builds").toAbsolutePath().normalize();
        BuildJobEntity job = job(BuildJobEntity.JOB_RELEASE, 7);
        assertEquals(root.resolve("artifacts").resolve(job.getId().toString()), BuildWorker.outputPath(root, job));
    }

    private static BuildJobEntity job(String type, int revision) {
        BuildJobEntity job = new BuildJobEntity();
        job.setId(UUID.randomUUID());
        job.setTemplateProjectId(UUID.randomUUID());
        job.setRevision(revision);
        job.setJobType(type);
        return job;
    }
}
