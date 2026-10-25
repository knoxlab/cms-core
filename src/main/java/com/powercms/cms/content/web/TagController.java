package com.powercms.cms.content.web;

import com.powercms.cms.content.TagService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 标签池建议与标签云（FR-CM-FTSUP-001~003，19.11 节）。 */
@RestController
@RequestMapping({"/api/v1/sites/{siteId}/tags", "/api/admin/sites/{siteId}/tags"})
class TagController {
  private final TagService service;

  TagController(TagService s) {
    service = s;
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping("/suggestions")
  List<String> suggestions(@PathVariable UUID siteId, @RequestParam(defaultValue = "") String prefix) {
    return service.suggestions(siteId, prefix);
  }

  @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
  @GetMapping("/cloud")
  List<TagService.TagStat> cloud(@PathVariable UUID siteId) {
    return service.cloud(siteId);
  }
}
