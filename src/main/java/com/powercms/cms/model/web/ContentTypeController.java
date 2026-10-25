package com.powercms.cms.model.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.powercms.cms.model.ContentModelValidator;
import com.powercms.cms.model.ContentTypeService;
import com.powercms.cms.model.TravelSitePresetService;
import com.powercms.cms.model.VideoPlatformPresetService;
import com.powercms.cms.model.dto.ContentTypeResponse;
import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.cms.model.entity.ContentTypeLifecycleEntity;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/sites/{siteId}/content-types", "/api/admin/sites/{siteId}/content-types"})
class ContentTypeController {
  private final ContentTypeService service;
  private final VideoPlatformPresetService videoPresetService;
  private final TravelSitePresetService travelPresetService;

  ContentTypeController(
      ContentTypeService s,
      VideoPlatformPresetService videoPresetService,
      TravelSitePresetService travelPresetService) {
    service = s;
    this.videoPresetService = videoPresetService;
    this.travelPresetService = travelPresetService;
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'model')")
  @PostMapping("/presets/video-platform")
  VideoPlatformPresetService.PresetResult installVideoPlatformPreset(@PathVariable UUID siteId) {
    return videoPresetService.install(siteId);
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'model')")
  @PostMapping("/presets/travel-lux")
  TravelSitePresetService.PresetResult installTravelSitePreset(@PathVariable UUID siteId) {
    return travelPresetService.install(siteId);
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping
  List<ContentTypeResponse> list(@PathVariable UUID siteId) {
    return service.list(siteId);
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping("/content-menu")
  List<ContentTypeResponse> contentMenu(@PathVariable UUID siteId) {
    return service.contentMenu(siteId);
  }

  record ContentMenuReq(boolean enabled, String label, Integer order) {}

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PutMapping("/{id}/content-menu")
  ContentTypeResponse updateContentMenu(@PathVariable UUID siteId, @PathVariable UUID id,
                                                 @RequestBody ContentMenuReq req) {
    return service.updateContentMenu(siteId, id, req.enabled(), req.label(), req.order());
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}")
  ContentTypeResponse get(@PathVariable UUID siteId, @PathVariable UUID id) {
    return service.get(siteId, id);
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'model')")
  @PostMapping
  ResponseEntity<ContentTypeResponse> create(
      @PathVariable UUID siteId, @Valid @RequestBody CreateContentTypeRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(siteId, req));
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PutMapping("/{id}")
  ContentTypeResponse update(
      @PathVariable UUID siteId,
      @PathVariable UUID id,
      @Valid @RequestBody CreateContentTypeRequest req) {
    return service.update(siteId, id, req);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PutMapping("/{id}/config")
  ContentTypeResponse updateConfig(
      @PathVariable UUID siteId, @PathVariable UUID id, @RequestBody JsonNode config) {
    return service.updateConfig(siteId, id, config);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PostMapping("/{id}/publish")
  ContentTypeResponse publish(
      @PathVariable UUID siteId,
      @PathVariable UUID id,
      @RequestBody(required = false) JsonNode body,
      Authentication auth) {
    // migrationRules 允许对象（规范）或字符串（兼容旧调用）；缺失时为 null → 迁移走默认规则
    JsonNode rules = body == null ? null : body.get("migrationRules");
    String normalized = rules == null || rules.isNull()
        ? null
        : rules.isTextual() ? rules.asText() : rules.toString();
    return service.publish(siteId, id, auth.getName(), normalized);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PostMapping("/{id}/new-version")
  ContentTypeResponse newVersion(@PathVariable UUID siteId, @PathVariable UUID id) {
    return service.newVersion(siteId, id);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}/versions")
  List<ContentTypeResponse> versions(@PathVariable UUID siteId, @PathVariable UUID id) {
    return service.versions(siteId, id);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}/versions/{version}")
  ContentTypeResponse version(
      @PathVariable UUID siteId, @PathVariable UUID id, @PathVariable int version) {
    return service.version(siteId, id, version);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}/diff")
  Map<String, Object> diff(
      @PathVariable UUID siteId,
      @PathVariable UUID id,
      @RequestParam int from,
      @RequestParam int to) {
    return service.diff(siteId, id, from, to);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}/references")
  List<Map<String, Object>> references(@PathVariable UUID siteId, @PathVariable UUID id) {
    return service.references(siteId, id);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}/field-types")
  List<Map<String, Object>> fieldTypes(@PathVariable UUID siteId, @PathVariable UUID id) {
    service.get(siteId, id);
    return service.fieldTypes();
  }

  /**
   * 系统字段目录（2026-09-22）：按对象类型（MEDIA 可加媒体种类）返回自动生成的系统字段清单。
   * 供模型设计器在**新建模型时**即可见系统字段并拖放排版（前端不再硬编码清单，单一权威在此）。
   */
  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping("/system-fields")
  List<ContentModelValidator.SystemField> systemFields(
      @PathVariable UUID siteId,
      @RequestParam String objectType,
      @RequestParam(required = false) String mediaKind) {
    return ContentModelValidator.systemFields(objectType, mediaKind);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @PostMapping("/{id}/preview")
  Map<String, Object> preview(
      @PathVariable UUID siteId, @PathVariable UUID id, @RequestBody JsonNode data) {
    return service.preview(siteId, id, data);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID siteId, @PathVariable UUID id) {
    service.delete(siteId, id);
    return ResponseEntity.noContent().build();
  }

  record CopyContentTypeReq(String name, String code, String icon) {}

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PostMapping("/{id}/copy")
  ResponseEntity<ContentTypeResponse> copy(
      @PathVariable UUID siteId, @PathVariable UUID id, @RequestBody(required = false) CopyContentTypeReq req) {
    String name = req == null ? null : req.name();
    String code = req == null ? null : req.code();
    String icon = req == null ? null : req.icon();
    return ResponseEntity.status(HttpStatus.CREATED).body(service.copy(siteId, id, name, code, icon));
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}/fields/{fieldId}/variables")
  com.fasterxml.jackson.databind.JsonNode fieldVariables(
      @PathVariable UUID siteId, @PathVariable UUID id, @PathVariable UUID fieldId) {
    return service.fieldVariables(siteId, id, fieldId);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PutMapping("/{id}/fields/{fieldId}/variables")
  com.powercms.cms.model.entity.FieldDefinitionEntity saveFieldVariables(
      @PathVariable UUID siteId,
      @PathVariable UUID id,
      @PathVariable UUID fieldId,
      @RequestBody com.fasterxml.jackson.databind.JsonNode variables) {
    return service.saveFieldVariables(siteId, id, fieldId, variables);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @DeleteMapping("/{id}/fields/{fieldId}/variables/{key}")
  com.powercms.cms.model.entity.FieldDefinitionEntity deleteFieldVariable(
      @PathVariable UUID siteId, @PathVariable UUID id, @PathVariable UUID fieldId, @PathVariable String key) {
    return service.deleteFieldVariable(siteId, id, fieldId, key);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @PostMapping("/{id}/fields/{fieldId}/custom-code/render")
  Map<String, Object> renderCustomCode(
      @PathVariable UUID siteId,
      @PathVariable UUID id,
      @PathVariable UUID fieldId,
      @RequestBody(required = false) JsonNode body) {
    var r = service.renderCustomCode(siteId, id, fieldId, body == null ? null : body.get("input"));
    return Map.of(
        "valid", r.valid(), "executed", r.executed(), "output", r.output(),
        "errors", r.errors(), "message", r.message());
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @PostMapping("/{id}/fields/{fieldId}/custom-code/validate")
  Map<String, Object> validateCustomCode(
      @PathVariable UUID siteId,
      @PathVariable UUID id,
      @PathVariable UUID fieldId,
      @RequestBody(required = false) JsonNode body) {
    var r = service.validateCustomCode(siteId, id, fieldId, body == null ? null : body.get("input"));
    return Map.of(
        "valid", r.valid(), "executed", r.executed(), "output", r.output(),
        "errors", r.errors(), "message", r.message());
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}/parent-candidates")
  List<Map<String, Object>> parentCandidates(@PathVariable UUID siteId, @PathVariable UUID id) {
    return service.parentCandidates(siteId, id);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'read')")
  @GetMapping("/{id}/lifecycle")
  List<ContentTypeLifecycleEntity> lifecycle(
      @PathVariable UUID siteId, @PathVariable UUID id) {
    return service.lifecycle(siteId, id);
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PutMapping("/{id}/lifecycle/{event}")
  ContentTypeLifecycleEntity saveLifecycle(
      @PathVariable UUID siteId,
      @PathVariable UUID id,
      @PathVariable String event,
      @RequestBody JsonNode body,
      Authentication auth) {
    return service.saveLifecycle(siteId, id, event, body, auth.getName());
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @DeleteMapping("/{id}/lifecycle/{event}")
  ResponseEntity<Void> deleteLifecycle(
      @PathVariable UUID siteId, @PathVariable UUID id, @PathVariable String event) {
    service.deleteLifecycle(siteId, id, event);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
  @PostMapping("/{id}/lifecycle/{event}/test")
  Map<String, Object> dryRunLifecycle(
      @PathVariable UUID siteId,
      @PathVariable UUID id,
      @PathVariable String event,
      @RequestBody JsonNode body) {
    return service.dryRunLifecycle(siteId, id, event, body);
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping("/quick-create")
  List<ContentTypeResponse> quickCreate(@PathVariable UUID siteId) {
    return service.quickCreate(siteId);
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @PostMapping("/quick-create/preview")
  Map<String, Object> previewDestination(@RequestBody JsonNode body) {
    JsonNode pattern =
        body.hasNonNull("destinationPattern") ? body.get("destinationPattern") : body.get("pattern");
    return service.previewDestination(pattern == null ? null : pattern.asText());
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @PostMapping("/var-name/suggest")
  Map<String, String> suggestVariableName(@RequestBody JsonNode body) {
    return service.suggestVariableName(
        body.path("name").asText(), body.path("fieldType").asText("STRING"));
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping("/var-name/reserved")
  Set<String> reservedVariableNames() {
    return service.reservedVariableNames();
  }
}
