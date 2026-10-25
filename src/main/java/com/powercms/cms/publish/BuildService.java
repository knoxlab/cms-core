package com.powercms.cms.publish;

import com.powercms.cms.publish.entity.BuildJobEntity;
import com.powercms.cms.publish.entity.BuildLogEntity;
import com.powercms.cms.publish.repo.BuildJobRepository;
import com.powercms.cms.publish.repo.BuildLogRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 构建任务服务（FR-IDE-005）。
 * P3 阶段：任务状态管理和日志流接口；实际容器构建在 P4 引入隔离 Worker 后对接。
 */
@Service
@Transactional
public class BuildService {

    private final BuildJobRepository jobRepo;
    private final BuildLogRepository logRepo;
    private final BuildWorker worker;

    BuildService(BuildJobRepository jobRepo, BuildLogRepository logRepo, BuildWorker worker) {
        this.jobRepo = jobRepo;
        this.logRepo = logRepo;
        this.worker = worker;
    }

    public record JobInfo(UUID id, UUID projectId, int revision, UUID releaseId,
                          String jobType, String status, String artifactUrl,
                          Instant startedAt, Instant finishedAt, Instant createdAt) {
        public static JobInfo from(BuildJobEntity j) {
            return new JobInfo(j.getId(), j.getTemplateProjectId(), j.getRevision(),
                j.getReleaseId(), j.getJobType(), j.getStatus(), j.getArtifactUrl(),
                j.getStartedAt(), j.getFinishedAt(), j.getCreatedAt());
        }
    }

    public record PhaseInfo(String phase, long durationMs) {}

    public record LogLine(long id, int seq, String level, String line, Instant createdAt) {
        public static LogLine from(BuildLogEntity l) {
            return new LogLine(l.getId(), l.getSeq(), l.getLevel(), l.getLine(), l.getCreatedAt());
        }
    }

    /** 社区版每日构建次数上限（矩阵 C4：10 次/日）。 */
    static final int MAX_BUILDS_PER_DAY = 10;
    /** 社区版构建并发上限（矩阵 C4：1）。 */
    static final int MAX_BUILD_CONCURRENCY = 1;

    public JobInfo createJob(UUID projectId, int revision, String jobType,
                              UUID releaseId, String createdBy) {
        Instant dayStart = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        if (jobRepo.countByCreatedAtAfter(dayStart) >= MAX_BUILDS_PER_DAY) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "社区版每日最多构建 " + MAX_BUILDS_PER_DAY + " 次");
        }
        String type = jobType != null ? jobType : BuildJobEntity.JOB_PREVIEW;
        if (!BuildJobEntity.JOB_PREVIEW.equals(type)
            && !BuildJobEntity.JOB_RELEASE.equals(type)
            && !BuildJobEntity.JOB_RELEASE_INCREMENTAL.equals(type)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不支持的构建类型: " + type);
        }
        if ((BuildJobEntity.JOB_RELEASE.equals(type) || BuildJobEntity.JOB_RELEASE_INCREMENTAL.equals(type))
            && releaseId == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "RELEASE 构建需要 releaseId");
        }
        var j = new BuildJobEntity();
        j.setTemplateProjectId(projectId);
        j.setRevision(revision);
        j.setJobType(type);
        j.setReleaseId(releaseId);
        j.setCreatedBy(createdBy);
        j.setCreatedAt(Instant.now());
        j = jobRepo.save(j);

        appendLog(j.getId(), "INFO", "构建任务已创建 type=" + type + " revision=" + revision);
        return JobInfo.from(j);
    }

    public JobInfo startJob(UUID jobId) {
        var j = requireJob(jobId);
        if (!BuildJobEntity.STATUS_QUEUED.equals(j.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "任务无法启动，当前状态：" + j.getStatus());
        }
        // C4：社区版构建并发上限为 1，必须在服务端断言。
        // 先断言再改状态——超限时抛异常，任务保持 QUEUED，调用方可以稍后重试或排队。
        long currentRunning = jobRepo.countByStatus(BuildJobEntity.STATUS_RUNNING);
        if (currentRunning >= MAX_BUILD_CONCURRENCY) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "社区版同一时间只允许 " + MAX_BUILD_CONCURRENCY + " 个构建运行中");
        }
        j.setStatus(BuildJobEntity.STATUS_RUNNING);
        j.setStartedAt(Instant.now());
        jobRepo.save(j);
        appendLog(j.getId(), "INFO", "构建开始...");
        java.util.concurrent.CompletableFuture.runAsync(() -> worker.execute(j.getId()));
        return JobInfo.from(j);
    }

    public JobInfo cancelJob(UUID jobId) {
        var j = requireJob(jobId);
        if (j.getFinishedAt() != null) {
            throw new BusinessException(ErrorCodes.CONFLICT, "任务已结束");
        }
        j.setStatus(BuildJobEntity.STATUS_CANCELLED);
        worker.cancel(jobId);
        j.setFinishedAt(Instant.now());
        jobRepo.save(j);
        appendLog(j.getId(), "WARN", "构建已取消");
        return JobInfo.from(j);
    }

    public void appendLog(UUID jobId, String level, String line) {
        var existing = logRepo.findByBuildJobIdOrderBySeq(jobId);
        int nextSeq = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSeq() + 1;
        logRepo.save(BuildLogEntity.write(jobId, nextSeq, level, line));
    }

    public List<LogLine> getLogs(UUID jobId) {
        return logRepo.findByBuildJobIdOrderBySeq(jobId).stream().map(LogLine::from).toList();
    }

    public LogLine getLogAfter(UUID jobId, int afterSeq) {
        return logRepo.findByBuildJobIdOrderBySeq(jobId).stream()
            .filter(l -> l.getSeq() == afterSeq + 1).findFirst()
            .map(LogLine::from).orElse(null);
    }

    public List<JobInfo> listJobs(UUID projectId) {
        return jobRepo.findByTemplateProjectIdOrderByCreatedAtDesc(projectId).stream()
            .map(JobInfo::from).toList();
    }

    public JobInfo getJob(UUID id) { return JobInfo.from(requireJob(id)); }

    private BuildJobEntity requireJob(UUID id) {
        return jobRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "构建任务不存在"));
    }
}
