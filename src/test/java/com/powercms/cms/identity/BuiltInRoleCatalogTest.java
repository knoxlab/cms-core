package com.powercms.cms.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInRoleCatalogTest {

    @Test
    void seedsContainChannelRoles() {
        assertThat(BuiltInRoleCatalog.seeds())
            .extracting(BuiltInRoleCatalog.Seed::code)
            .contains(BuiltInRoleCatalog.FRONT_END_USER,
                BuiltInRoleCatalog.BACK_END_USER,
                BuiltInRoleCatalog.ANONYMOUS);
    }

    @Test
    void channelRolesAreOrgScopeWithoutCapabilities() {
        assertThat(BuiltInRoleCatalog.scope(BuiltInRoleCatalog.BACK_END_USER)).isEqualTo("ORG");
        assertThat(BuiltInRoleCatalog.scope(BuiltInRoleCatalog.FRONT_END_USER)).isEqualTo("ORG");
        assertThat(BuiltInRoleCatalog.scope(BuiltInRoleCatalog.ANONYMOUS)).isEqualTo("ORG");
        assertThat(BuiltInRoleCatalog.capabilities(BuiltInRoleCatalog.BACK_END_USER)).isEmpty();
        assertThat(BuiltInRoleCatalog.capabilities(BuiltInRoleCatalog.FRONT_END_USER)).isEmpty();
    }

    @Test
    void builtInIncludesChannelAndPseudoRoles() {
        assertThat(BuiltInRoleCatalog.isBuiltIn("front-end-user")).isTrue();
        assertThat(BuiltInRoleCatalog.isBuiltIn("back-end-user")).isTrue();
        assertThat(BuiltInRoleCatalog.isBuiltIn("anonymous")).isTrue();
        assertThat(BuiltInRoleCatalog.isBuiltIn("owner")).isTrue();
        assertThat(BuiltInRoleCatalog.isChannelRole("owner")).isTrue();
    }

    @Test
    void channelRolesCannotBeCreatedAsCustomRole() {
        assertThat(BuiltInRoleCatalog.isBuiltIn("front-end-user")).isTrue();
        assertThat(BuiltInRoleCatalog.isBuiltIn("back-end-user")).isTrue();
    }

    @Test
    void frontEndUserHasNoLayoutBinding() {
        assertThat(BackendLayoutCatalog.layoutsForRole(BuiltInRoleCatalog.FRONT_END_USER)).isEmpty();
        assertThat(BackendLayoutCatalog.layoutsForRole(BuiltInRoleCatalog.ANONYMOUS)).isEmpty();
        assertThat(BackendLayoutCatalog.layoutsForRole(BuiltInRoleCatalog.OWNER)).isEmpty();
    }

    @Test
    void systemAdminGetsAllLayouts() {
        assertThat(BackendLayoutCatalog.layoutsForRole(BuiltInRoleCatalog.SYSTEM_ADMIN))
            .containsAll(BackendLayoutCatalog.ALL);
    }

    @Test
    void editorGetsContentAndMediaLayouts() {
        assertThat(BackendLayoutCatalog.layoutsForRole(BuiltInRoleCatalog.EDITOR))
            .containsExactlyInAnyOrder("content", "media");
    }

    @Test
    void reviewerAndPublisherLayoutsStayWithinContentWorkflow() {
        assertThat(BackendLayoutCatalog.layoutsForRole(BuiltInRoleCatalog.REVIEWER))
            .containsExactlyInAnyOrder("workflow", "content");
        assertThat(BackendLayoutCatalog.layoutsForRole(BuiltInRoleCatalog.CONTENT_PUBLISHER))
            .containsExactlyInAnyOrder("content", "media", "workflow");
    }
}
