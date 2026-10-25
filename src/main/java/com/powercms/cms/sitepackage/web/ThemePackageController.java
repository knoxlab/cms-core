package com.powercms.cms.sitepackage.web;

import com.powercms.cms.sitepackage.ThemePackageService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sites/{siteId}/visual/themes")
class ThemePackageController {
    record RestoreRequest(String message) {}
    record SetParentRequest(UUID parentThemeId) {}
    record ShareRequest(UUID consumerSiteId, Integer pinnedVersion) {}
    record BrowserFileRequest(@jakarta.validation.constraints.NotBlank String path, String content, int expectedVersion) {}
    private final ThemePackageService service;
    ThemePackageController(ThemePackageService service) { this.service = service; }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ThemePackageService.ImportResult> importTheme(@PathVariable UUID siteId,
            @RequestPart("file") MultipartFile file, @RequestPart(value = "message", required = false) String message, Authentication auth) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.importZip(siteId, file.getBytes(), actor(auth), message));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/{themeId}/files")
    List<ThemePackageService.BrowserFile> files(@PathVariable UUID siteId, @PathVariable UUID themeId) { return service.browserFiles(siteId, themeId); }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PutMapping("/{themeId}/files")
    ThemePackageService.BrowserFile saveFile(@PathVariable UUID siteId, @PathVariable UUID themeId, @jakarta.validation.Valid @RequestBody BrowserFileRequest request, Authentication auth) { return service.saveBrowserTextFile(siteId, themeId, request.path(), request.content(), request.expectedVersion(), actor(auth)); }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/{themeId}/export")
    ResponseEntity<byte[]> exportTheme(@PathVariable UUID siteId, @PathVariable UUID themeId,
            @RequestParam(required = false) Integer version, Authentication auth) {
        var result = service.exportZip(siteId, themeId, version, actor(auth));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(result.fileName()).build().toString())
            .header("X-Theme-File-Count", String.valueOf(result.files().size())).body(result.content());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/{themeId}/history")
    List<ThemePackageService.RevisionInfo> history(@PathVariable UUID siteId, @PathVariable UUID themeId) {
        return service.revisions(siteId, themeId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/{themeId}/history/{version}")
    ThemePackageService.RevisionDetail detail(@PathVariable UUID siteId, @PathVariable UUID themeId, @PathVariable int version) {
        return service.revision(siteId, themeId, version);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @DeleteMapping("/{themeId}/history/{version}")
    ResponseEntity<Void> deleteRevision(@PathVariable UUID siteId, @PathVariable UUID themeId,
                                        @PathVariable int version, Authentication auth) {
        service.deleteRevision(siteId, themeId, version, actor(auth));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/{themeId}/diff/{v1}/{v2}")
    List<ThemePackageService.DiffEntry> diff(@PathVariable UUID siteId, @PathVariable UUID themeId,
                                             @PathVariable int v1, @PathVariable int v2) {
        return service.diff(siteId, themeId, v1, v2);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PostMapping("/{themeId}/restore/{version}")
    ThemePackageService.ImportResult restore(@PathVariable UUID siteId, @PathVariable UUID themeId,
                                              @PathVariable int version, @RequestBody(required = false) RestoreRequest request,
                                              Authentication auth) {
        return service.restore(siteId, themeId, version, actor(auth), request == null ? null : request.message());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PutMapping("/{themeId}/parent")
    ThemePackageService.RevisionInfo setParent(@PathVariable UUID siteId, @PathVariable UUID themeId,
                                               @RequestBody SetParentRequest request, Authentication auth) {
        return service.setParent(siteId, themeId, request == null ? null : request.parentThemeId(), actor(auth));
    }
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @GetMapping("/{themeId}/shares")
    List<ThemePackageService.ShareInfo> shares(@PathVariable UUID siteId, @PathVariable UUID themeId) {
        return service.shares(siteId, themeId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PutMapping("/{themeId}/shares")
    ThemePackageService.ShareInfo grant(@PathVariable UUID siteId, @PathVariable UUID themeId,
            @RequestBody ShareRequest request, Authentication auth) {
        return service.grantShare(siteId, themeId, request.consumerSiteId(), request.pinnedVersion(), actor(auth));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PutMapping("/{themeId}/shares/{consumerSiteId}")
    ThemePackageService.ShareInfo updateShare(@PathVariable UUID siteId, @PathVariable UUID themeId,
            @PathVariable UUID consumerSiteId, @RequestBody ShareRequest request, Authentication auth) {
        if (request == null || request.pinnedVersion() == null) throw new com.powercms.common.error.BusinessException(com.powercms.common.error.ErrorCodes.VALIDATION_FAILED, "必须指定固定版本");
        return service.updateShareVersion(siteId, themeId, consumerSiteId, request.pinnedVersion(), actor(auth));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @DeleteMapping("/{themeId}/shares/{consumerSiteId}")
    ResponseEntity<Void> revoke(@PathVariable UUID siteId, @PathVariable UUID themeId,
            @PathVariable UUID consumerSiteId, Authentication auth) {
        service.revokeShare(siteId, themeId, consumerSiteId, actor(auth)); return ResponseEntity.noContent().build();
    }

    private static String actor(Authentication auth) { return auth == null || auth.getName() == null ? "system" : auth.getName(); }
}
