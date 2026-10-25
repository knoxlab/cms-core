package com.powercms.cms.publish;

import com.powercms.cms.content.ContentPublishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * AFTER_COMMIT auto SSG pipeline: draft → freeze → RELEASE build. Never deploys.
 * Gated by {@code powercms.ssg.auto-build-enabled} (default false).
 */
@Component
public class SsgPublishCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SsgPublishCoordinator.class);
    private static final DateTimeFormatter NAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ReleaseService releases;
    private final BuildService builds;
    private final boolean enabled;
    private final long coalesceDelayMs;
    private final ScheduledExecutorService scheduler;
    private final Map<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> running = new ConcurrentHashMap<>();

    SsgPublishCoordinator(ReleaseService releases, BuildService builds,
                          @Value("${powercms.ssg.auto-build-enabled:false}") boolean enabled,
                          @Value("${powercms.ssg.coalesce-delay-ms:1000}") long coalesceDelayMs) {
        this.releases = releases;
        this.builds = builds;
        this.enabled = enabled;
        this.coalesceDelayMs = Math.max(0, coalesceDelayMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ssg-publish-coordinator");
            t.setDaemon(true);
            return t;
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentPublished(ContentPublishedEvent event) {
        if (!enabled || event == null || event.siteId() == null) return;
        schedule(event.siteId());
    }

    /** 权限收紧后请求重新冻结并构建。自动构建关闭时由协调器自己跳过。 */
    public void requestRebuild(UUID siteId) {
        if (siteId == null) return;
        if (!enabled) {
            log.warn("access rebuild requested for site {} but SSG auto-build is disabled", siteId);
            return;
        }
        schedule(siteId);
    }

    void schedule(UUID siteId) {
        pending.compute(siteId, (id, previous) -> {
            if (previous != null) previous.cancel(false);
            return scheduler.schedule(() -> runPipeline(id), coalesceDelayMs, TimeUnit.MILLISECONDS);
        });
    }

    void runPipeline(UUID siteId) {
        pending.remove(siteId);
        if (running.putIfAbsent(siteId, Boolean.TRUE) != null) {
            schedule(siteId);
            return;
        }
        try {
            String name = "Auto SSG " + NAME_TS.format(Instant.now());
            var draft = releases.createDraft(siteId, name, "auto-build from content publish");
            var frozen = releases.freeze(draft.id());
            if (frozen.templateProjectId() == null || frozen.templateRevision() == null) {
                log.warn("SSG auto-build skipped for site {}: no FTL template project", siteId);
                return;
            }
            var job = builds.createJob(frozen.templateProjectId(), frozen.templateRevision(),
                com.powercms.cms.publish.entity.BuildJobEntity.JOB_RELEASE, frozen.id(), "ssg-auto");
            builds.startJob(job.id());
            log.info("SSG auto-build started site={} release={} job={}", siteId, frozen.id(), job.id());
        } catch (Exception ex) {
            log.error("SSG auto-build failed for site {}: {}", siteId, ex.getMessage(), ex);
        } finally {
            running.remove(siteId);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
