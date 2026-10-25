package com.powercms.cms.content.relation.web;

import com.powercms.cms.content.relation.ContentRelationService;
import com.powercms.cms.content.relation.ContentRelationService.RelationDef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 内容关系定义管理端点（FR-CM-RELATION-001~005，19.11 节）。 */
@RestController
@RequestMapping({
  "/api/v1/sites/{siteId}/content-relations",
  "/api/admin/sites/{siteId}/content-relations"
})
class ContentRelationController {
  private final ContentRelationService service;

  ContentRelationController(ContentRelationService s) {
    service = s;
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping
  List<RelationDef> list(@PathVariable UUID siteId) {
    return service.listDefinitions(siteId);
  }

  record CreateRelationReq(
      @NotBlank String relationName,
      @NotBlank String cardinality,
      @NotNull UUID sourceTypeId,
      @NotNull UUID targetTypeId,
      String description) {}

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')")
  @PostMapping
  ResponseEntity<RelationDef> create(
      @PathVariable UUID siteId,
      @jakarta.validation.Valid @RequestBody CreateRelationReq req,
      Authentication auth) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.createDefinition(
                siteId,
                req.relationName(),
                req.cardinality(),
                req.sourceTypeId(),
                req.targetTypeId(),
                req.description(),
                auth.getName()));
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')")
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID siteId, @PathVariable UUID id) {
    service.deleteDefinition(siteId, id);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping("/search-by-related")
  List<Map<String, Object>> searchByRelated(
      @PathVariable UUID siteId, @RequestParam String query) {
    return service.searchByRelated(siteId, query);
  }
}
