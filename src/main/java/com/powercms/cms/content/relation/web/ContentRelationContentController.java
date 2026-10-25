package com.powercms.cms.content.relation.web;

import com.powercms.cms.content.relation.ContentRelationService;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 内容级关系端点（FR-CM-RELATION-013，19.11 节）。 */
@RestController
@RequestMapping({"/api/v1/content/{id}/relations", "/api/content/{id}/relations"})
class ContentRelationContentController {
  private final ContentRelationService service;

  ContentRelationContentController(ContentRelationService s) {
    service = s;
  }

  @PreAuthorize("@siteAuthorization.content(authentication,#id,'read')")
  @GetMapping
  List<Map<String, Object>> relations(@PathVariable UUID id) {
    return service.relations(id);
  }

  record AddRelationReq(@NotNull UUID targetContentId) {}

  @PreAuthorize("@siteAuthorization.content(authentication,#id,'write')")
  @PostMapping("/{relationName}")
  Map<String, Object> add(
      @PathVariable UUID id,
      @PathVariable String relationName,
      @jakarta.validation.Valid @RequestBody AddRelationReq req,
      Authentication auth) {
    return service.addRelation(id, relationName, req.targetContentId(), auth.getName());
  }

  @PreAuthorize("@siteAuthorization.content(authentication,#id,'write')")
  @DeleteMapping("/{relationName}/{targetId}")
  ResponseEntity<Void> remove(
      @PathVariable UUID id, @PathVariable String relationName, @PathVariable UUID targetId) {
    service.removeRelation(id, relationName, targetId);
    return ResponseEntity.noContent().build();
  }
}
