package com.powercms.cms.delivery;

import com.powercms.cms.model.entity.ContentTypeEntity;

import java.util.List;

/**
 * 由已发布内容模型合成站点级 GraphQL SDL（FR-API-003）。
 * 每个模型生成 PascalCase 类型 + camelCase 查询入口（单条 byId / 分页列表）。
 */
final class SdlComposer {

    private SdlComposer() {}

    static String compose(List<ContentTypeEntity> publishedTypes) {
        StringBuilder types = new StringBuilder();
        StringBuilder queries = new StringBuilder();

        queries.append("  contents(siteId: ID!, contentTypeId: ID, categoryId: ID, locale: String, ")
            .append("page: Int = 0, size: Int = 20): [DeliveryContent!]!\n");
        queries.append("  content(siteId: ID!, id: ID!, locale: String): DeliveryContent\n");

        for (ContentTypeEntity ct : publishedTypes) {
            String typeName = pascal(ct.getCode());
            queries.append("  ").append(camel(ct.getCode())).append("(siteId: ID!, id: ID!): ").append(typeName).append('\n');
            queries.append("  ").append(camel(ct.getCode())).append("List(siteId: ID!, categoryId: ID, page: Int = 0, size: Int = 20): [")
                .append(typeName).append("!]!\n");

            types.append("type ").append(typeName).append(" {\n");
            types.append("  id: ID!\n");
            types.append("  title: String\n");
            types.append("  fieldValues: String!\n");
            types.append("}\n\n");
        }

        types.append("type DeliveryContent {\n")
            .append("  id: ID!\n")
            .append("  title: String!\n")
            .append("  categoryId: ID!\n")
            .append("  contentTypeId: ID!\n")
            .append("  version: Int!\n")
            .append("  fieldValues: String!\n")
            .append("  updatedAt: String!\n")
            .append("  media: [MediaSummary!]!\n")
            .append("}\n\n");

        types.append("type MediaSummary {\n")
            .append("  assetId: ID!\n")
            .append("  kind: String\n")
            .append("  posterUrl: String\n")
            .append("  durationMs: Int\n")
            .append("  width: Int\n")
            .append("  height: Int\n")
            .append("  playUrlTemplate: String\n")
            .append("}\n");

        return "type Query {\n" + queries + "}\n\n" + types;
    }

    static String pascal(String code) {
        String[] parts = code.split("[^a-zA-Z0-9]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)))
                .append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        if (sb.isEmpty() || Character.isDigit(sb.charAt(0))) sb.insert(0, "Type");
        return sb.toString();
    }

    static String camel(String code) {
        String pascal = pascal(code);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }
}
