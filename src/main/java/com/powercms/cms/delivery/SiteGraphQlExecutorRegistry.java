package com.powercms.cms.delivery;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeRuntimeWiring;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点级 GraphQL 执行器缓存：schema 变更（模型发布）时以 fingerprint 失效并重建。
 * 候选 SDL 先经 SchemaGenerator 构建成功才放入缓存，保证失败不影响现有执行器（FR-API-003）。
 */
@Service
public class SiteGraphQlExecutorRegistry {

    private final record Entry(GraphQL engine, String fingerprint) {}

    private final ConcurrentHashMap<UUID, Entry> engines = new ConcurrentHashMap<>();
    private final DynamicSchemaRegistry schemas;
    private final DeliveryQueryResolver resolver;

    SiteGraphQlExecutorRegistry(DynamicSchemaRegistry schemas, DeliveryQueryResolver resolver) {
        this.schemas = schemas;
        this.resolver = resolver;
    }

    public GraphQL engineFor(UUID siteId) {
        DynamicSchemaRegistry.SchemaVersion version = schemas.get(siteId);
        if (version == null) return null;
        Entry entry = engines.get(siteId);
        if (entry == null || !entry.fingerprint().equals(version.fingerprint())) {
            entry = build(siteId, version);
            engines.put(siteId, entry);
        }
        return entry.engine();
    }

    private Entry build(UUID siteId, DynamicSchemaRegistry.SchemaVersion version) {
        var typeRegistry = new graphql.schema.idl.SchemaParser().parse(version.sdl());
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
            .type(TypeRuntimeWiring.newTypeWiring("Query")
                .dataFetcher("contents", env -> {
                    UUID sid = uuid(env.getArgument("siteId"));
                    return resolver.contents(sid,
                        uuid(env.getArgument("contentTypeId")),
                        uuid(env.getArgument("categoryId")),
                        intOf(env.getArgument("page")), intOf(env.getArgument("size")));
                })
                .dataFetcher("content", env -> resolver.content(
                    uuid(env.getArgument("siteId")), uuid(env.getArgument("id"))))
                .build())
            .build();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(typeRegistry, wiring);
        return new Entry(GraphQL.newGraphQL(schema).build(), version.fingerprint());
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static Integer intOf(Object value) {
        return value == null ? null : Integer.valueOf(value.toString());
    }
}
