package com.powercms.cms.publish.web;

import com.powercms.cms.publish.ReleaseService;
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
class ReleaseController {

    private final ReleaseService service;

    ReleaseController(ReleaseService service) { this.service = service; }

    record CreateReq(@NotBlank String name, String description) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'deploy')")
    @PostMapping("/sites/{siteId}/releases")
    ResponseEntity<ReleaseService.ReleaseInfo> create(@PathVariable UUID siteId,
                                                        @Valid @RequestBody CreateReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createDraft(siteId, req.name(), req.description()));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/releases")
    List<ReleaseService.ReleaseInfo> list(@PathVariable UUID siteId) { return service.list(siteId); }

    @PreAuthorize("@siteAuthorization.release(authentication, #id, 'read')")
    @GetMapping("/releases/{id}")
    ReleaseService.ReleaseInfo get(@PathVariable UUID id) { return service.get(id); }

    @PreAuthorize("@siteAuthorization.release(authentication, #id, 'deploy')")
    @PostMapping("/releases/{id}/freeze")
    ResponseEntity<ReleaseService.ReleaseInfo> freeze(@PathVariable UUID id) {
        return ResponseEntity.ok(service.freeze(id));
    }

    @PreAuthorize("@siteAuthorization.release(authentication, #id, 'deploy')")
    @PostMapping("/releases/{id}/deploy")
    ResponseEntity<ReleaseService.ReleaseInfo> deploy(@PathVariable UUID id) {
        return ResponseEntity.ok(service.markDeployed(id));
    }

    @PreAuthorize("@siteAuthorization.release(authentication, #id, 'read')")
    @GetMapping("/releases/{id}/pages")
    List<ReleaseService.ReleasePageInfo> pages(@PathVariable UUID id) { return service.getPages(id); }
    @PreAuthorize("@siteAuthorization.release(authentication, #id, 'read')")
    @GetMapping("/releases/{id}/themes")
    List<ReleaseService.ReleaseThemeInfo> themes(@PathVariable UUID id) { return service.getThemes(id); }

    @PreAuthorize("@siteAuthorization.release(authentication, #id, 'read')")
    @GetMapping("/releases/{id}/contents")
    List<ReleaseService.ReleaseContentInfo> contents(@PathVariable UUID id) { return service.getContents(id); }
}
