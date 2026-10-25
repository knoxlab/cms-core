package com.powercms.cms.identity;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-AGT-MCP-004：Token 权限分级 L0/L1/L2 → mcp:read/draft/publish。 */
class McpScopesTest {

    @Test
    void levelOfComputesHighestLevel() {
        assertEquals(-1, McpScopes.levelOf(Set.of()));
        assertEquals(McpScopes.L0, McpScopes.levelOf(Set.of(McpScopes.READ)));
        assertEquals(McpScopes.L1, McpScopes.levelOf(Set.of(McpScopes.READ, McpScopes.DRAFT)));
        assertEquals(McpScopes.L2, McpScopes.levelOf(Set.of(McpScopes.READ, McpScopes.DRAFT, McpScopes.PUBLISH)));
        assertEquals(McpScopes.L2, McpScopes.levelOf(Set.of("*")));
    }

    @Test
    void permitsChecksRequiredLevel() {
        Set<String> l0 = Set.of(McpScopes.READ);
        assertTrue(McpScopes.permits(l0, McpScopes.L0));
        assertFalse(McpScopes.permits(l0, McpScopes.L1));
        assertFalse(McpScopes.permits(l0, McpScopes.L2));

        Set<String> l1 = Set.of(McpScopes.READ, McpScopes.DRAFT);
        assertTrue(McpScopes.permits(l1, McpScopes.L0));
        assertTrue(McpScopes.permits(l1, McpScopes.L1));
        assertFalse(McpScopes.permits(l1, McpScopes.L2));
    }

    @Test
    void isMcpScopeRecognizesNamespace() {
        assertTrue(McpScopes.isMcpScope("mcp:read"));
        assertTrue(McpScopes.isMcpScope("MCP:DRAFT"));
        assertFalse(McpScopes.isMcpScope("read"));
        assertFalse(McpScopes.isMcpScope(null));
    }
}
