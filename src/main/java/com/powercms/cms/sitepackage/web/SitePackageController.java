package com.powercms.cms.sitepackage.web;

import com.powercms.cms.sitepackage.SitePackagePrecheck;
import com.powercms.cms.sitepackage.SitePackageService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
@RequestMapping("/api/v1")
class SitePackageController {

    private final SitePackageService service;

    SitePackageController(SitePackageService service) { this.service = service; }

    /**
     * 导入前预检：返回清单信息、安全/校验违规与冲突报告（FR-PACKAGE-005）。
     * 无副作用，可安全重复调用。
     */
    @PostMapping(value = "/site-packages/precheck",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<SitePackagePrecheck.Report> precheck(
            @RequestAttribute("organizationId") UUID organizationId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(service.precheck(organizationId, bytes(file)));
    }

    /**
     * 导入站点包 ZIP（FR-PACKAGE-001～007）。
     *
     * @param conflictStrategy 冲突处理策略（FAIL/RENAME/OVERWRITE/SKIP）；存在可确认冲突时必填
     * @param conflictsToken   预检签发的确认令牌；存在可确认冲突时必填
     */
    @PostMapping(value = "/site-packages/import",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<SitePackageService.ImportResult> importPackage(
            @RequestAttribute("organizationId") UUID organizationId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conflictStrategy", required = false) String conflictStrategy,
            @RequestParam(value = "conflictsToken", required = false) String conflictsToken) {
        var result = service.importPackage(organizationId, bytes(file), conflictStrategy, conflictsToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** 导出站点为站点包 ZIP（FR-PACKAGE-008） */
    @GetMapping("/sites/{siteId}/export")
    ResponseEntity<byte[]> exportPackage(@PathVariable UUID siteId) {
        byte[] zip = service.exportPackage(siteId);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=site-package.zip")
            .body(zip);
    }

    /** 列出已导入的站点包 */
    @GetMapping("/site-packages")
    List<SitePackageService.PkgInfo> listPackages() { return service.listPackages(); }

    @GetMapping("/site-packages/{id}")
    SitePackageService.PkgInfo getPackage(@PathVariable UUID id) { return service.getPackage(id); }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new com.powercms.common.error.BusinessException(
                com.powercms.common.error.ErrorCodes.BAD_REQUEST, "文件读取失败: " + e.getMessage());
        }
    }
}
