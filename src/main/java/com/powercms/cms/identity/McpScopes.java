package com.powercms.cms.identity;

import java.util.Locale;
import java.util.Set;

/**
 * MCP Token 权限分级（FR-AGT-MCP-004，映射 FR-IAM-005 API Token scopes）。
 * 分级：L0 只读 / L1 草稿 / L2 发布，映射 scope 常量 {@code mcp:read}/{@code mcp:draft}/{@code mcp:publish}。
 * 分级只做「Token 维度」的能力上限，站点级能力与对象级 ACL 由调用方另行求交（权限不越过绑定用户）。
 */
public final class McpScopes {

    /** L0 只读。 */
    public static final String READ = "mcp:read";
    /** L1 草稿（含只读）。 */
    public static final String DRAFT = "mcp:draft";
    /** L2 发布（含草稿与只读）。 */
    public static final String PUBLISH = "mcp:publish";

    public static final int L0 = 0;
    public static final int L1 = 1;
    public static final int L2 = 2;

    /** 全部 MCP scope（不含站点能力 scope）。 */
    public static final Set<String> ALL = Set.of(READ, DRAFT, PUBLISH);

    private McpScopes() {}

    /** 是否为 MCP 命名空间 scope（大小写不敏感）。 */
    public static boolean isMcpScope(String scope) {
        if (scope == null) return false;
        return ALL.contains(scope.trim().toLowerCase(Locale.ROOT));
    }

    /** 计算 token scopes 覆盖到的最高 MCP 级别；无 MCP scope 返回 -1。 */
    public static int levelOf(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return -1;
        if (scopes.contains("*")) return L2;
        int level = -1;
        if (scopes.contains(READ)) level = L0;
        if (scopes.contains(DRAFT)) level = Math.max(level, L1);
        if (scopes.contains(PUBLISH)) level = Math.max(level, L2);
        return level;
    }

    /** 指定级别是否被 token scopes 覆盖（工具所需最低级别 ≤ token 级别）。 */
    public static boolean permits(Set<String> scopes, int requiredLevel) {
        return levelOf(scopes) >= requiredLevel;
    }
}
