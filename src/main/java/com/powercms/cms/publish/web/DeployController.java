package com.powercms.cms.publish.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.publish.DeployService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class DeployController {

    private final DeployService service;

    DeployController(DeployService service) { this.service = service; }

    // ---------- Target ----------

    record CreateTargetReq(@NotBlank String name, String targetType, String config) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'deploy')")
    @PostMapping("/sites/{siteId}/deploy-targets")
    ResponseEntity<DeployService.TargetInfo> createTarget(@PathVariable UUID siteId,
                                                            @Valid @RequestBody CreateTargetReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createTarget(siteId, req.name(), req.targetType(), req.config()));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/deploy-targets")
    List<DeployService.TargetInfo> listTargets(@PathVariable UUID siteId) { return service.listTargets(siteId); }

    @PreAuthorize("@siteAuthorization.target(authentication, #id, 'read')")
    @GetMapping("/deploy-targets/{id}")
    DeployService.TargetInfo getTarget(@PathVariable UUID id) { return service.getTarget(id); }

    @PreAuthorize("@siteAuthorization.target(authentication, #id, 'deploy')")
    @PutMapping("/deploy-targets/{id}")
    DeployService.TargetInfo updateTarget(@PathVariable UUID id, @RequestBody String config) {
        return service.updateTarget(id, config);
    }

    @PreAuthorize("@siteAuthorization.target(authentication, #id, 'deploy')")
    @DeleteMapping("/deploy-targets/{id}")
    ResponseEntity<Void> deleteTarget(@PathVariable UUID id) {
        service.deleteTarget(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Artifact ----------

    record CreateArtifactReq(UUID buildJobId, @NotNull UUID releaseId,
                              @NotBlank String artifactPath, int fileCount, long sizeBytes,
                              String routeManifest, String fileManifest, String inputDigest, String buildType) {}

    @PreAuthorize("@siteAuthorization.release(authentication, #req.releaseId(), 'deploy')")
    @PostMapping("/build-artifacts")
    ResponseEntity<DeployService.ArtifactInfo> createArtifact(@Valid @RequestBody CreateArtifactReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createArtifact(req.buildJobId(), req.releaseId(), req.artifactPath(),
                req.fileCount(), req.sizeBytes(),
                req.routeManifest(), req.fileManifest(), req.inputDigest(), req.buildType()));
    }

    @PreAuthorize("@siteAuthorization.artifact(authentication, #id, 'read')")
    @GetMapping("/build-artifacts/{id}")
    DeployService.ArtifactInfo getArtifact(@PathVariable UUID id) { return service.getArtifact(id); }

    @PreAuthorize("@siteAuthorization.release(authentication, #releaseId, 'read')")
    @GetMapping("/releases/{releaseId}/artifacts")
    List<DeployService.ArtifactInfo> listArtifacts(@PathVariable UUID releaseId) { return service.listArtifacts(releaseId); }

    // ---------- Deployment ----------

    record CreateDeploymentReq(@NotNull UUID releaseId, @NotNull UUID deployTargetId,
                                UUID artifactId, String buildType, Instant scheduledAt) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'deploy')")
    @PostMapping("/sites/{siteId}/deployments")
    ResponseEntity<DeployService.DeploymentInfo> createDeployment(@PathVariable UUID siteId,
                                                                    @Valid @RequestBody CreateDeploymentReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createDeployment(siteId, req.releaseId(), req.deployTargetId(),
                req.artifactId(), req.buildType(), req.scheduledAt(), AuditService.currentActor()));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/deployments")
    List<DeployService.DeploymentInfo> listDeployments(@PathVariable UUID siteId) {
        return service.listDeployments(siteId);
    }

    @PreAuthorize("@siteAuthorization.target(authentication, #targetId, 'read')")
    @GetMapping("/deploy-targets/{targetId}/deployments")
    List<DeployService.DeploymentInfo> listDeploymentsByTarget(@PathVariable UUID targetId) {
        return service.listDeploymentsByTarget(targetId);
    }

    @PreAuthorize("@siteAuthorization.deployment(authentication, #id, 'read')")
    @GetMapping("/deployments/{id}")
    DeployService.DeploymentInfo getDeployment(@PathVariable UUID id) { return service.getDeployment(id); }

    @PreAuthorize("@siteAuthorization.target(authentication, #targetId, 'read')")
    @GetMapping("/deploy-targets/{targetId}/current")
    DeployService.DeploymentInfo current(@PathVariable UUID targetId) { return service.getCurrentDeployment(targetId); }

    @PreAuthorize("@siteAuthorization.deployment(authentication, #id, 'deploy')")
    @PostMapping("/deployments/{id}/publish")
    ResponseEntity<DeployService.DeploymentInfo> publish(@PathVariable UUID id) { return ResponseEntity.ok(service.publish(id)); }

    record AdvanceReq(@NotBlank String status) {}

    @PreAuthorize("@siteAuthorization.deployment(authentication, #id, 'deploy')")
    @PostMapping("/deployments/{id}/advance")
    ResponseEntity<DeployService.DeploymentInfo> advance(@PathVariable UUID id,
                                                           @Valid @RequestBody AdvanceReq req) {
        return ResponseEntity.ok(service.advanceStatus(id, req.status()));
    }

    record CompleteReq(boolean preHealthOk, boolean postHealthOk) {}

    @PreAuthorize("@siteAuthorization.deployment(authentication, #id, 'deploy')")
    @PostMapping("/deployments/{id}/complete")
    ResponseEntity<DeployService.DeploymentInfo> complete(@PathVariable UUID id,
                                                            @Valid @RequestBody CompleteReq req) {
        return ResponseEntity.ok(service.deploySuccess(id, req.preHealthOk(), req.postHealthOk()));
    }

    record FailReq(String note) {}

    @PreAuthorize("@siteAuthorization.deployment(authentication, #id, 'deploy')")
    @PostMapping("/deployments/{id}/fail")
    ResponseEntity<DeployService.DeploymentInfo> fail(@PathVariable UUID id,
                                                        @Valid @RequestBody FailReq req) {
        return ResponseEntity.ok(service.deployFailed(id, req.note()));
    }

    @PreAuthorize("@siteAuthorization.deployment(authentication, #id, 'deploy')")
    @PostMapping("/deployments/{id}/rollback")
    ResponseEntity<DeployService.DeploymentInfo> rollback(@PathVariable UUID id) {
        return ResponseEntity.ok(service.rollback(id));
    }

    @PreAuthorize("@siteAuthorization.deployment(authentication, #id, 'deploy')")
    @PostMapping("/deployments/{id}/cancel")
    ResponseEntity<Void> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }
}
