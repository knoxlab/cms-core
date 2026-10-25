package com.powercms.cms.publish;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaticPublishGuardTest {

    @TempDir Path temp;

    @Test
    void scrubDeletesRestrictedPageAndSnippetHidesHistoricalReleases() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID openId = UUID.randomUUID();
        UUID closedId = UUID.randomUUID();
        Path release = temp.resolve("releases").resolve(UUID.randomUUID().toString());
        Files.createDirectories(release.resolve("open"));
        Files.createDirectories(release.resolve("closed"));
        Files.writeString(release.resolve("open/index.html"), "open");
        Files.writeString(release.resolve("closed/index.html"), "secret");

        ContentItemEntity open = new ContentItemEntity();
        open.setId(openId);
        ContentItemEntity closed = new ContentItemEntity();
        closed.setId(closedId);
        ContentItemRepository items = mock(ContentItemRepository.class);
        ObjectPermissionService permissions = mock(ObjectPermissionService.class);
        when(items.findBySiteIdAndRoutePathAndStatus(siteId, "/open", "PUBLISHED")).thenReturn(Optional.of(open));
        when(items.findBySiteIdAndRoutePathAndStatus(siteId, "/closed", "PUBLISHED")).thenReturn(Optional.of(closed));
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, openId, ObjectType.VIEW)).thenReturn(true);
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, closedId, ObjectType.VIEW)).thenReturn(false);

        StaticPublishGuard guard = new StaticPublishGuard(items, permissions, "");
        int removed = guard.seal(release, siteId);

        assertThat(removed).isEqualTo(1);
        assertThat(release.resolve("open/index.html")).exists();
        assertThat(release.resolve("closed/index.html")).doesNotExist();
        String snippet = Files.readString(temp.resolve("nginx-delivery.conf"));
        assertThat(snippet).contains("location ^~ /releases/ { return 404; }");
        assertThat(snippet).contains("return 404;");
        String server = Files.readString(temp.resolve("nginx.server.conf"));
        assertThat(server).contains("include ").contains("nginx-delivery.conf");
    }

    @Test
    void scrubRemovesPageWhoseAccessVariesByClient() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        Path release = temp.resolve("releases").resolve(UUID.randomUUID().toString());
        Files.createDirectories(release.resolve("timed"));
        Files.writeString(release.resolve("timed/index.html"), "window");
        ContentItemEntity page = new ContentItemEntity();
        page.setId(pageId);
        ContentItemRepository items = mock(ContentItemRepository.class);
        ObjectPermissionService permissions = mock(ObjectPermissionService.class);
        com.powercms.cms.identity.permission.ProgramAccessService program =
            mock(com.powercms.cms.identity.permission.ProgramAccessService.class);
        when(items.findBySiteIdAndRoutePathAndStatus(siteId, "/timed", "PUBLISHED")).thenReturn(Optional.of(page));
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, pageId, ObjectType.VIEW)).thenReturn(true);
        when(program.variesByRequest(ObjectType.CONTENT, pageId)).thenReturn(true);

        StaticPublishGuard guard = new StaticPublishGuard(items, permissions, "", program);
        assertThat(guard.seal(release, siteId)).isEqualTo(1);
        assertThat(release.resolve("timed/index.html")).doesNotExist();
    }
}
