package com.powercms.cms.sitepackage.web;

import com.powercms.cms.sitepackage.TemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class TemplateController {

    private final TemplateService service;

    TemplateController(TemplateService service) { this.service = service; }

    // ---------- Project ----------

    record CreateProjReq(@NotBlank String name, String projectType) {}

    @PreAuthorize("@siteAuthorization.templateWork(authentication, #siteId)")
    @PostMapping("/sites/{siteId}/template-projects")
    ResponseEntity<TemplateService.ProjInfo> createProject(@PathVariable UUID siteId,
                                                             @Valid @RequestBody CreateProjReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createProject(siteId, req.name(), req.projectType()));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/template-projects")
    List<TemplateService.ProjInfo> listProjects(@PathVariable UUID siteId) { return service.listProjects(siteId); }

    @PreAuthorize("@siteAuthorization.project(authentication, #id, 'read')")
    @GetMapping("/template-projects/{id}")
    TemplateService.ProjInfo getProject(@PathVariable UUID id) { return service.getProject(id); }

    // ---------- Files ----------

    record FileReq(@NotBlank String path, @NotBlank String content) {}
    record DeleteFileReq(@NotBlank String path) {}

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/files")
    List<TemplateService.FileInfo> listFiles(@PathVariable UUID projectId) { return service.listFiles(projectId); }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'write')")
    @PutMapping("/template-projects/{projectId}/files")
    TemplateService.FileInfo saveFile(@PathVariable UUID projectId,
                                       @Valid @RequestBody FileReq req) {
        return service.saveFile(projectId, req.path(), req.content());
    }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/files-content")
    TemplateService.FileInfo getFile(@PathVariable UUID projectId, @RequestParam String path) {
        return service.getFile(projectId, path);
    }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'write')")
    @DeleteMapping("/template-projects/{projectId}/files")
    ResponseEntity<Void> deleteFile(@PathVariable UUID projectId,
                                     @Valid @RequestBody DeleteFileReq req) {
        service.deleteFile(projectId, req.path());
        return ResponseEntity.noContent().build();
    }

    // ---------- Revision ----------

    record CommitReq(String author, String message) {}

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'write')")
    @PostMapping("/template-projects/{projectId}/commits")
    ResponseEntity<TemplateService.RevisionInfo> commit(@PathVariable UUID projectId,
                                                          @Valid @RequestBody CommitReq req) {
        return ResponseEntity.ok(service.commit(projectId,
            req.author() != null ? req.author() : "system",
            req.message() != null ? req.message() : ""));
    }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/revisions")
    List<TemplateService.RevisionInfo> revisions(@PathVariable UUID projectId) { return service.revisions(projectId); }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/revisions/{version}")
    TemplateService.RevisionInfo getRevision(@PathVariable UUID projectId, @PathVariable int version) {
        return service.getRevision(projectId, version);
    }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'write')")
    @PostMapping("/template-projects/{projectId}/restore/{version}")
    ResponseEntity<Void> restore(@PathVariable UUID projectId, @PathVariable int version) {
        service.restore(projectId, version);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/diff/{v1}/{v2}")
    List<TemplateService.DiffEntry> diff(@PathVariable UUID projectId, @PathVariable int v1, @PathVariable int v2) {
        return service.diff(projectId, v1, v2);
    }

    // ========== FR-IDE-004: lockfile & preflight ==========

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/lockfile-status")
    TemplateService.LockfileStatus lockfileStatus(@PathVariable UUID projectId) {
        return service.validateLockfile(projectId);
    }

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/preflight-release")
    TemplateService.PreflightResult preflightRelease(@PathVariable UUID projectId) {
        return service.preflightRelease(projectId);
    }

    // ========== FR-IDE-008: dependency audit ==========

    @PreAuthorize("@siteAuthorization.project(authentication, #projectId, 'read')")
    @GetMapping("/template-projects/{projectId}/dependency-audit")
    TemplateService.DependencyReport dependencyAudit(@PathVariable UUID projectId) {
        return service.auditDependencies(projectId);
    }
}
