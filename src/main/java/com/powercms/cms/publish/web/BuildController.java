package com.powercms.cms.publish.web;

import com.powercms.cms.publish.BuildService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class BuildController {

    private final BuildService service;

    BuildController(BuildService service) { this.service = service; }

    record CreateJobReq(@NotNull UUID projectId, int revision, String jobType, UUID releaseId) {}

    @PreAuthorize("@siteAuthorization.project(authentication, #req.projectId(), 'write')")
    @PostMapping("/build-jobs")
    ResponseEntity<BuildService.JobInfo> create(@Valid @RequestBody CreateJobReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createJob(req.projectId(), req.revision(), req.jobType(), req.releaseId(), "admin"));
    }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/build-jobs")
    List<BuildService.JobInfo> list(@PathVariable UUID projectId) { return service.listJobs(projectId); }

    @PreAuthorize("@siteAuthorization.build(authentication, #id, 'read')")
    @GetMapping("/build-jobs/{id}")
    BuildService.JobInfo get(@PathVariable UUID id) { return service.getJob(id); }

    @PreAuthorize("@siteAuthorization.build(authentication, #id, 'write')")
    @PostMapping("/build-jobs/{id}/start")
    ResponseEntity<BuildService.JobInfo> start(@PathVariable UUID id) {
        return ResponseEntity.ok(service.startJob(id));
    }

    @PreAuthorize("@siteAuthorization.build(authentication, #id, 'write')")
    @PostMapping("/build-jobs/{id}/cancel")
    ResponseEntity<BuildService.JobInfo> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(service.cancelJob(id));
    }

    // ---------- Logs ----------

    @PreAuthorize("@siteAuthorization.build(authentication, #id, 'read')")
    @GetMapping("/build-jobs/{id}/logs")
    List<BuildService.LogLine> logs(@PathVariable UUID id) { return service.getLogs(id); }

    /** SSE 风格尾随日志：返回 afterSeq 之后的第一行（前端轮询用） */
    @PreAuthorize("@siteAuthorization.build(authentication, #id, 'read')")
    @GetMapping("/build-jobs/{id}/logs/after")
    BuildService.LogLine logAfter(@PathVariable UUID id, @RequestParam(defaultValue = "-1") int seq) {
        return service.getLogAfter(id, seq);
    }
}
