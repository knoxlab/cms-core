package com.powercms.cms.publish;

import com.powercms.cms.content.ContentPublishedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsgPublishCoordinatorTest {
    @Mock ReleaseService releases;
    @Mock BuildService builds;
    SsgPublishCoordinator coordinator;

    @AfterEach void tearDown() {
        if (coordinator != null) coordinator.shutdown();
    }

    @Test void disabledByDefaultDoesNothing() {
        coordinator = new SsgPublishCoordinator(releases, builds, false, 10);
        coordinator.onContentPublished(event(UUID.randomUUID()));
        verify(releases, never()).createDraft(any(), anyString(), anyString());
    }

    @Test void coalescesRapidEventsAndStartsReleaseBuild() throws Exception {
        UUID site = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        AtomicInteger drafts = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);

        when(releases.createDraft(eq(site), anyString(), anyString())).thenAnswer(i -> {
            drafts.incrementAndGet();
            return new ReleaseService.ReleaseInfo(releaseId, site, i.getArgument(1), i.getArgument(2),
                "DRAFT", null, null, null, null, null, java.util.List.of());
        });
        when(releases.freeze(releaseId)).thenReturn(new ReleaseService.ReleaseInfo(
            releaseId, site, "Auto", "d", "FROZEN", projectId, 3, "digest", null, null, java.util.List.of()));
        when(builds.createJob(eq(projectId), eq(3), eq("RELEASE"), eq(releaseId), eq("ssg-auto")))
            .thenReturn(new BuildService.JobInfo(jobId, projectId, 3, releaseId, "RELEASE", "QUEUED", null, null, null, null));
        when(builds.startJob(jobId)).thenAnswer(i -> {
            started.countDown();
            return new BuildService.JobInfo(jobId, projectId, 3, releaseId, "RELEASE", "RUNNING", null, null, null, null);
        });

        coordinator = new SsgPublishCoordinator(releases, builds, true, 50);
        coordinator.onContentPublished(event(site));
        coordinator.onContentPublished(event(site));
        coordinator.onContentPublished(event(site));

        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        verify(releases, timeout(3000).times(1)).freeze(releaseId);
        verify(builds, timeout(3000).times(1)).startJob(jobId);
        assertThat(drafts.get()).isEqualTo(1);
        verify(releases, atMost(1)).createDraft(eq(site), anyString(), anyString());
    }

    @Test void skipsWhenNoFtlProject() {
        UUID site = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        when(releases.createDraft(eq(site), anyString(), anyString())).thenReturn(
            new ReleaseService.ReleaseInfo(releaseId, site, "Auto", "d", "DRAFT", null, null, null, null, null, java.util.List.of()));
        when(releases.freeze(releaseId)).thenReturn(
            new ReleaseService.ReleaseInfo(releaseId, site, "Auto", "d", "FROZEN", null, null, "digest", null, null, java.util.List.of()));

        coordinator = new SsgPublishCoordinator(releases, builds, true, 20);
        coordinator.onContentPublished(event(site));
        verify(builds, timeout(2000).times(0)).createJob(any(), anyInt(), anyString(), any(), anyString());
        verify(releases, timeout(2000).times(1)).freeze(releaseId);
    }

    private static ContentPublishedEvent event(UUID site) {
        return new ContentPublishedEvent(UUID.randomUUID(), site, UUID.randomUUID(), UUID.randomUUID(), "/x", 1, "DRAFT");
    }
}
