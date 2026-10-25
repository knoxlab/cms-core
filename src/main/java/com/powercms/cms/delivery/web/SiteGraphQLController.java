package com.powercms.cms.delivery.web;

import com.powercms.cms.delivery.DynamicSchemaRegistry;
import com.powercms.cms.delivery.SiteGraphQlExecutorRegistry;
import graphql.ExecutionInput;
import graphql.GraphQL;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 站点级动态 GraphQL 执行端点（FR-API-003）。
 * POST /graphql/sites/{siteId} 按 siteId 选择该站点已发布模型合成的 schema 执行；
 * 站点 schema 尚未构建时返回 404，回退到全局静态 /graphql。
 */
@RestController
@RequestMapping("/graphql/sites")
public class SiteGraphQLController {

    private final SiteGraphQlExecutorRegistry executors;
    private final DynamicSchemaRegistry schemas;

    public SiteGraphQLController(SiteGraphQlExecutorRegistry executors, DynamicSchemaRegistry schemas) {
        this.executors = executors;
        this.schemas = schemas;
    }

    public record GraphQlRequest(String query, Map<String, Object> variables, String operationName) {}

    @PostMapping(value = "/{siteId}", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> execute(@PathVariable java.util.UUID siteId,
                                                       @RequestBody GraphQlRequest request) {
        if (schemas.get(siteId) == null) {
            return ResponseEntity.notFound().build();
        }
        GraphQL engine = executors.engineFor(siteId);
        if (engine == null) return ResponseEntity.notFound().build();
        ExecutionInput input = ExecutionInput.newExecutionInput()
            .query(request.query())
            .variables(request.variables() == null ? Map.of() : request.variables())
            .operationName(request.operationName())
            .build();
        return ResponseEntity.ok(engine.execute(input).toSpecification());
    }

    /** 当前生效 schema 的 SDL 与指纹（运维/契约校验用）。 */
    @GetMapping("/{siteId}/schema")
    public ResponseEntity<Map<String, Object>> schema(@PathVariable java.util.UUID siteId) {
        var version = schemas.get(siteId);
        if (version == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
            "sdl", version.sdl(),
            "fingerprint", version.fingerprint(),
            "builtAt", version.builtAt()));
    }
}
