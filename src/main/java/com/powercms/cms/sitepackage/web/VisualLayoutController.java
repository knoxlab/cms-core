package com.powercms.cms.sitepackage.web;

import com.powercms.cms.sitepackage.VisualLayoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class VisualLayoutController {
    private final VisualLayoutService service;
    private final com.powercms.cms.platform.security.SiteAuthorization siteAuthorization;

    VisualLayoutController(VisualLayoutService service,
                           com.powercms.cms.platform.security.SiteAuthorization siteAuthorization) {
        this.service = service;
        this.siteAuthorization = siteAuthorization;
    }

    record ThemeReq(@NotBlank String name, String description, String config, String status) {}
    record TemplateReq(@NotBlank String name, UUID themeId, String description, String layoutJson, String status, String message) {}
    record ContainerReq(@NotBlank String name, String allowedComponentTypes, String config, List<VisualLayoutService.ContainerStructureInput> containerStructures, String message) {}
    record ZoneReq(@NotBlank String zoneKey, UUID containerId, int sortOrder, String config) {}
    record AclReq(List<VisualLayoutService.AclEntry> entries) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/visual/themes")
    List<VisualLayoutService.ThemeInfo> themes(@PathVariable UUID siteId,
                                               @RequestParam(defaultValue = "false") boolean includeUnrecognized) {
        return service.themes(siteId, includeUnrecognized);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/visual/themes/{id}")
    VisualLayoutService.ThemeInfo theme(@PathVariable UUID siteId, @PathVariable UUID id) { return service.getTheme(siteId, id); }

    @PreAuthorize("@siteAuthorization.templateWork(authentication, #siteId)")
    @PutMapping("/sites/{siteId}/visual/themes/{id}")
    VisualLayoutService.ThemeInfo updateTheme(@PathVariable UUID siteId, @PathVariable UUID id,
                                              @Valid @RequestBody ThemeReq r) {
        return service.saveTheme(siteId, id, r.name(), r.description(), r.config(), r.status());
    }

    @PreAuthorize("@siteAuthorization.templateWork(authentication, #siteId)")
    @PostMapping("/sites/{siteId}/visual/themes")
    ResponseEntity<VisualLayoutService.ThemeInfo> createTheme(@PathVariable UUID siteId,
                                                              @Valid @RequestBody ThemeReq r) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.saveTheme(siteId, null, r.name(), r.description(), r.config(), r.status()));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/visual/templates")
    List<VisualLayoutService.TemplateInfo> templates(@PathVariable UUID siteId,
                                                     Authentication auth,
                                                     @RequestParam(required = false) UUID themeId,
                                                     @RequestParam(required = false) String q) {
        return service.templates(siteId, themeId, q).stream()
            .filter(t -> siteAuthorization.visualTemplate(auth, t.id(), "view"))
            .toList();
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #id, 'view')")
    @GetMapping("/sites/{siteId}/visual/templates/{id}")
    VisualLayoutService.TemplateInfo getTemplate(@PathVariable UUID siteId, @PathVariable UUID id) {
        return service.getTemplate(siteId, id);
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #id, 'view')")
    @GetMapping("/sites/{siteId}/visual/templates/{id}/document")
    VisualLayoutService.TemplateDocument document(@PathVariable UUID siteId, @PathVariable UUID id) {
        return service.document(siteId, id);
    }

    @PreAuthorize("@siteAuthorization.templateWork(authentication, #siteId)")
    @PostMapping("/sites/{siteId}/visual/templates")
    ResponseEntity<VisualLayoutService.TemplateInfo> createTemplate(@PathVariable UUID siteId,
                                                                    Authentication auth,
                                                                    @Valid @RequestBody TemplateReq r) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.saveTemplate(siteId, null, r.themeId(), r.name(), r.description(),
                r.layoutJson(), r.status(), actor(auth), r.message()));
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #id, (#r.status() != null && #r.status().equalsIgnoreCase('PUBLISHED')) ? 'publish' : 'edit')")
    @PutMapping("/sites/{siteId}/visual/templates/{id}")
    VisualLayoutService.TemplateInfo updateTemplate(@PathVariable UUID siteId, @PathVariable UUID id,
                                                    Authentication auth, @Valid @RequestBody TemplateReq r) {
        return service.saveTemplate(siteId, id, r.themeId(), r.name(), r.description(),
            r.layoutJson(), r.status(), actor(auth), r.message());
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #id, 'publish')")
    @PostMapping("/sites/{siteId}/visual/templates/{id}/publish")
    VisualLayoutService.TemplateInfo publishTemplate(@PathVariable UUID siteId, @PathVariable UUID id,
                                                     Authentication auth, @RequestBody(required = false) TemplateReq r) {
        var current = service.getTemplate(siteId, id);
        return service.saveTemplate(siteId, id, current.themeId(), current.name(), current.description(),
            current.layoutJson(), "PUBLISHED", actor(auth),
            r == null || r.message() == null ? "发布模板" : r.message());
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #id, 'edit')")
    @DeleteMapping("/sites/{siteId}/visual/templates/{id}")
    ResponseEntity<Void> deleteTemplate(@PathVariable UUID siteId, @PathVariable UUID id, Authentication auth) {
        service.deleteTemplate(siteId, id, actor(auth));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'view')")
    @GetMapping("/sites/{siteId}/visual/templates/{templateId}/revisions")
    List<VisualLayoutService.RevisionInfo> revisions(@PathVariable UUID siteId, @PathVariable UUID templateId) {
        return service.revisions(siteId, templateId);
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'view')")
    @GetMapping("/sites/{siteId}/visual/templates/{templateId}/revisions/{version}")
    VisualLayoutService.RevisionInfo revision(@PathVariable UUID siteId, @PathVariable UUID templateId,
                                              @PathVariable int version) {
        return service.getRevision(siteId, templateId, version);
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'view')")
    @GetMapping("/sites/{siteId}/visual/templates/{templateId}/revisions/{version}/snapshot")
    String revisionSnapshot(@PathVariable UUID siteId, @PathVariable UUID templateId, @PathVariable int version) {
        return service.revisionSnapshot(siteId, templateId, version);
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'view')")
    @GetMapping("/sites/{siteId}/visual/templates/{templateId}/diff/{v1}/{v2}")
    List<VisualLayoutService.DiffEntry> diff(@PathVariable UUID siteId, @PathVariable UUID templateId,
                                             @PathVariable int v1, @PathVariable int v2) {
        return service.diff(siteId, templateId, v1, v2);
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'edit')")
    @PostMapping("/sites/{siteId}/visual/templates/{templateId}/restore/{version}")
    VisualLayoutService.TemplateInfo restore(@PathVariable UUID siteId, @PathVariable UUID templateId,
                                             @PathVariable int version, Authentication auth) {
        return service.restore(siteId, templateId, version, actor(auth));
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'view')")
    @GetMapping("/sites/{siteId}/visual/templates/{templateId}/acl")
    List<VisualLayoutService.AclEntry> getAcl(@PathVariable UUID siteId, @PathVariable UUID templateId) {
        service.getTemplate(siteId, templateId);
        return service.listAcl(templateId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PutMapping("/sites/{siteId}/visual/templates/{templateId}/acl")
    List<VisualLayoutService.AclEntry> replaceAcl(@PathVariable UUID siteId, @PathVariable UUID templateId,
                                                  Authentication auth, @RequestBody AclReq req) {
        return service.replaceAcl(siteId, templateId, req.entries(), actor(auth));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/visual/containers")
    List<VisualLayoutService.ContainerInfo> containers(@PathVariable UUID siteId) {
        return service.containers(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/visual/containers/{id}")
    VisualLayoutService.ContainerInfo container(@PathVariable UUID siteId, @PathVariable UUID id) { return service.getContainer(siteId, id); }

    @PreAuthorize("@siteAuthorization.templateWork(authentication, #siteId)")
    @PostMapping("/sites/{siteId}/visual/containers")
    ResponseEntity<VisualLayoutService.ContainerInfo> createContainer(@PathVariable UUID siteId,
                                                                       Authentication auth, @Valid @RequestBody ContainerReq r) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.saveContainer(siteId, null, r.name(), r.config(), r.containerStructures(), actor(auth), r.message()));
    }

    @PreAuthorize("@siteAuthorization.templateWork(authentication, #siteId)")
    @PutMapping("/sites/{siteId}/visual/containers/{id}")
    VisualLayoutService.ContainerInfo updateContainer(@PathVariable UUID siteId, @PathVariable UUID id,
                                                      Authentication auth, @Valid @RequestBody ContainerReq r) {
        return service.saveContainer(siteId, id, r.name(), r.config(), r.containerStructures(), actor(auth), r.message());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/visual/containers/{id}/revisions")
    List<VisualLayoutService.ContainerRevisionInfo> containerRevisions(@PathVariable UUID siteId, @PathVariable UUID id) { return service.containerRevisions(siteId, id); }

    @PreAuthorize("@siteAuthorization.templateWork(authentication, #siteId)")
    @PostMapping("/sites/{siteId}/visual/containers/{id}/restore/{version}")
    VisualLayoutService.ContainerInfo restoreContainer(@PathVariable UUID siteId, @PathVariable UUID id, @PathVariable int version, Authentication auth) { return service.restoreContainer(siteId, id, version, actor(auth)); }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'edit')")
    @PutMapping("/sites/{siteId}/visual/templates/{templateId}/zones/{zoneId}")
    VisualLayoutService.TemplateInfo updateZone(@PathVariable UUID siteId, @PathVariable UUID templateId,
                                                @PathVariable UUID zoneId, Authentication auth,
                                                @Valid @RequestBody ZoneReq r) {
        return service.saveZone(siteId, templateId, zoneId, r.zoneKey(), r.containerId(), r.sortOrder(),
            r.config(), actor(auth));
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'edit')")
    @DeleteMapping("/sites/{siteId}/visual/templates/{templateId}/zones/{zoneId}")
    VisualLayoutService.TemplateInfo deleteZone(@PathVariable UUID siteId, @PathVariable UUID templateId,
                                                @PathVariable UUID zoneId, Authentication auth) {
        return service.deleteZone(siteId, templateId, zoneId, actor(auth));
    }

    @PreAuthorize("@siteAuthorization.visualTemplate(authentication, #templateId, 'edit')")
    @PostMapping("/sites/{siteId}/visual/templates/{templateId}/zones")
    VisualLayoutService.TemplateInfo createZone(@PathVariable UUID siteId, @PathVariable UUID templateId,
                                                Authentication auth, @Valid @RequestBody ZoneReq r) {
        return service.saveZone(siteId, templateId, null, r.zoneKey(), r.containerId(), r.sortOrder(),
            r.config(), actor(auth));
    }

    private static String actor(Authentication auth) {
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }
}
