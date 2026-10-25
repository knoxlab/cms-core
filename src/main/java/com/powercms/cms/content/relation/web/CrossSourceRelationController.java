package com.powercms.cms.content.relation.web;

import com.powercms.cms.content.relation.CrossSourceRelationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-AI-SRC-003 跨源关系端点：关系模板管理 + 跨源导航（来源域分组）。
 * <p>管理端：{@code /api/v1/sites/{siteId}/cross-source/...}
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/cross-source")
public class CrossSourceRelationController {

    private final CrossSourceRelationService service;

    public CrossSourceRelationController(CrossSourceRelationService service) {
        this.service = service;
    }

    // ---------- 关系模板 ----------

    @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
    @GetMapping("/templates")
    List<Map<String, Object>> listTemplates(@PathVariable UUID siteId) {
        return service.listTemplates(siteId);
    }

    record CreateTemplateReq(@NotNull UUID datasourceId, @NotBlank String name,
                             @NotBlank String relationName, @NotBlank String anchorField,
                             @NotBlank String recordField) {}

    @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')")
    @PostMapping("/templates")
    ResponseEntity<Map<String, Object>> createTemplate(@PathVariable UUID siteId,
                                                       @Valid @RequestBody CreateTemplateReq req,
                                                       Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTemplate(
            siteId, req.name(), req.datasourceId(), req.relationName(),
            req.anchorField(), req.recordField(), auth.getName()));
    }

    record UpdateTemplateStatusReq(@NotBlank String status) {}

    @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')")
    @PutMapping("/templates/{id}/status")
    Map<String, Object> updateStatus(@PathVariable UUID siteId, @PathVariable UUID id,
                                     @Valid @RequestBody UpdateTemplateStatusReq req,
                                     Authentication auth) {
        return service.updateTemplateStatus(siteId, id, req.status(), auth.getName());
    }

    @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')")
    @DeleteMapping("/templates/{id}")
    ResponseEntity<Void> deleteTemplate(@PathVariable UUID siteId, @PathVariable UUID id,
                                        Authentication auth) {
        service.deleteTemplate(siteId, id, auth.getName());
        return ResponseEntity.noContent().build();
    }

    // ---------- 跨源导航 ----------

    /** 产品入口跨源导航：关联条目按来源域（CMS/DAM/PIM）分组标注。 */
    @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
    @GetMapping("/navigation/{contentId}")
    Map<String, Object> navigation(@PathVariable UUID siteId, @PathVariable UUID contentId) {
        return service.navigation(contentId);
    }
}
