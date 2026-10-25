package com.powercms.cms.delivery.web;

import com.powercms.cms.delivery.DynamicSchemaRegistry;
import com.powercms.common.error.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 动态 schema 管理（FR-API-003）：手动重建站点 schema（模型发布事件也会自动触发）。
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/graphql-schema")
public class DynamicSchemaAdminController {

    private static final Logger log = LoggerFactory.getLogger(DynamicSchemaAdminController.class);

    private final DynamicSchemaRegistry registry;

    @Autowired
    public DynamicSchemaAdminController(DynamicSchemaRegistry registry) {
        this.registry = registry;
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable UUID siteId) {
        try {
            var version = registry.rebuildForSite(siteId);
            return ResponseEntity.ok(Map.of(
                "fingerprint", version.fingerprint(),
                "builtAt", version.builtAt()));
        } catch (Exception ex) {
            log.warn("schema rebuild failed for site {}: {}", siteId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("SCHEMA_BUILD_FAILED",
                    ex.getMessage() == null ? "schema 构建失败" : ex.getMessage(),
                    UUID.randomUUID().toString()));
        }
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping
    public ResponseEntity<Map<String, Object>> current(@PathVariable UUID siteId) {
        var version = registry.get(siteId);
        if (version == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
            "sdl", version.sdl(),
            "fingerprint", version.fingerprint(),
            "builtAt", version.builtAt()));
    }
}
