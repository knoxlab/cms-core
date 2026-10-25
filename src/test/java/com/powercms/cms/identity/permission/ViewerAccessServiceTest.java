package com.powercms.cms.identity.permission;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViewerAccessServiceTest {

    private final ObjectPermissionService permissions = mock(ObjectPermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ViewerAccessService service = new ViewerAccessService(permissions, users);
    private final UUID contentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void publicContentStaysVisibleToFrontendMemberWhenHasRightIsFalse() {
        UserEntity member = new UserEntity();
        member.setKind(UserEntity.KIND_FRONTEND);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);
        when(permissions.hasRight(userId, ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(false);

        assertThat(service.visible(userId, ObjectType.CONTENT, contentId)).isTrue();
    }

    @Test
    void memberOnlyContentVisibleWhenMemberHasRight() {
        UserEntity member = new UserEntity();
        member.setKind(UserEntity.KIND_FRONTEND);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(false);
        when(permissions.hasRight(userId, ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);

        assertThat(service.visible(userId, ObjectType.CONTENT, contentId)).isTrue();
    }

    @Test
    void memberDeniedWhenNeitherAnonymousNorRight() {
        UserEntity member = new UserEntity();
        member.setKind(UserEntity.KIND_FRONTEND);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(false);
        when(permissions.hasRight(userId, ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(false);

        assertThat(service.visible(userId, ObjectType.CONTENT, contentId)).isFalse();
    }

    @Test
    void anonymousUsesOnlyAnonymousRight() {
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);
        assertThat(service.visible(null, ObjectType.CONTENT, contentId)).isTrue();
    }

    @Test
    void categoryChainBlocksContentWhenFolderChainAllows() {
        ContentItemRepository contents = mock(ContentItemRepository.class);
        ViewerAccessService gated = new ViewerAccessService(permissions, users, contents);
        UUID categoryId = UUID.randomUUID();
        ContentItemEntity item = new ContentItemEntity();
        item.setCategoryId(categoryId);
        when(contents.findById(contentId)).thenReturn(Optional.of(item));
        when(permissions.isBroken(ObjectType.CONTENT, contentId)).thenReturn(false);
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);
        when(permissions.hasAnonymousRight(ObjectType.TAXONOMY, categoryId, ObjectType.VIEW)).thenReturn(false);

        assertThat(gated.visible(null, ObjectType.CONTENT, contentId)).isFalse();
    }

    @Test
    void brokenContentIgnoresCategoryChain() {
        ContentItemRepository contents = mock(ContentItemRepository.class);
        ViewerAccessService gated = new ViewerAccessService(permissions, users, contents);
        when(permissions.isBroken(ObjectType.CONTENT, contentId)).thenReturn(true);
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);

        assertThat(gated.visible(null, ObjectType.CONTENT, contentId)).isTrue();
        verify(permissions, never()).hasAnonymousRight(eq(ObjectType.TAXONOMY), any(), any());
    }

    @Test
    void memberPassesOnlyWhenBothChainsAllow() {
        ContentItemRepository contents = mock(ContentItemRepository.class);
        ViewerAccessService gated = new ViewerAccessService(permissions, users, contents);
        UserEntity member = new UserEntity();
        member.setKind(UserEntity.KIND_FRONTEND);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        UUID categoryId = UUID.randomUUID();
        ContentItemEntity item = new ContentItemEntity();
        item.setCategoryId(categoryId);
        when(contents.findById(contentId)).thenReturn(Optional.of(item));
        when(permissions.isBroken(ObjectType.CONTENT, contentId)).thenReturn(false);
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(false);
        when(permissions.hasRight(userId, ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);
        when(permissions.hasAnonymousRight(ObjectType.TAXONOMY, categoryId, ObjectType.VIEW)).thenReturn(false);
        when(permissions.hasRight(userId, ObjectType.TAXONOMY, categoryId, ObjectType.VIEW)).thenReturn(true);

        assertThat(gated.visible(userId, ObjectType.CONTENT, contentId)).isTrue();

        when(permissions.hasRight(userId, ObjectType.TAXONOMY, categoryId, ObjectType.VIEW)).thenReturn(false);
        assertThat(gated.visible(userId, ObjectType.CONTENT, contentId)).isFalse();
    }

    @Test
    void ipRestrictionDeniesViewAndDownloadStaysOffWithoutSwitch() {
        ProgramAccessService programAccess = mock(ProgramAccessService.class);
        ViewerAccessService gated = new ViewerAccessService(permissions, users, null, programAccess);
        when(permissions.hasAnonymousRight(ObjectType.MEDIA, contentId, ObjectType.VIEW)).thenReturn(true);
        when(programAccess.denyReason(eq(ObjectType.MEDIA), eq(contentId), nullable(String.class), any(), eq(false)))
            .thenReturn(com.powercms.common.error.ErrorCodes.ACCESS_IP_DENIED);

        assertThat(gated.visible(null, ObjectType.MEDIA, contentId)).isFalse();

        when(programAccess.denyReason(eq(ObjectType.MEDIA), eq(contentId), nullable(String.class), any(), eq(false))).thenReturn(null);
        when(programAccess.resolveDownload(ObjectType.MEDIA, contentId))
            .thenReturn(new ProgramAccessService.DownloadDecision(false, null));
        assertThat(gated.downloadable(null, ObjectType.MEDIA, contentId)).isFalse();
    }

    @Test
    void downloadSwitchOffAndMissingRightReportDifferentCodes() {
        ProgramAccessService programAccess = mock(ProgramAccessService.class);
        ViewerAccessService gated = new ViewerAccessService(permissions, users, null, programAccess);
        when(permissions.hasAnonymousRight(ObjectType.MEDIA, contentId, ObjectType.VIEW)).thenReturn(true);
        when(programAccess.denyReason(eq(ObjectType.MEDIA), eq(contentId), nullable(String.class), any(), eq(false)))
            .thenReturn(null);
        when(programAccess.variesByRequest(ObjectType.MEDIA, contentId)).thenReturn(false);

        // 总闸关闭：换谁来都下不了 → DOWNLOAD_DISABLED
        when(programAccess.resolveDownload(ObjectType.MEDIA, contentId))
            .thenReturn(new ProgramAccessService.DownloadDecision(false, "SITE"));
        assertThat(gated.decide(null, ObjectType.MEDIA, contentId, true).reason())
            .isEqualTo(com.powercms.common.error.ErrorCodes.DOWNLOAD_DISABLED);

        // 总闸已开但主体没有 download 权利 → DOWNLOAD_NOT_GRANTED（应去改授权，而不是开总闸）
        when(programAccess.resolveDownload(ObjectType.MEDIA, contentId))
            .thenReturn(new ProgramAccessService.DownloadDecision(true, "SITE"));
        when(permissions.hasAnonymousRight(ObjectType.MEDIA, contentId, ObjectType.DOWNLOAD)).thenReturn(false);
        assertThat(gated.decide(null, ObjectType.MEDIA, contentId, true).reason())
            .isEqualTo(com.powercms.common.error.ErrorCodes.DOWNLOAD_NOT_GRANTED);

        when(permissions.hasAnonymousRight(ObjectType.MEDIA, contentId, ObjectType.DOWNLOAD)).thenReturn(true);
        assertThat(gated.decide(null, ObjectType.MEDIA, contentId, true).allowed()).isTrue();
    }

    @Test
    void conditionRestrictedSuccessIsNotSharedCacheable() {
        AccessCacheScope.reset();
        ProgramAccessService programAccess = mock(ProgramAccessService.class);
        ViewerAccessService gated = new ViewerAccessService(permissions, users, null, programAccess);
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);
        when(programAccess.denyReason(eq(ObjectType.CONTENT), eq(contentId), nullable(String.class), any(), eq(false)))
            .thenReturn(null);
        when(programAccess.variesByRequest(ObjectType.CONTENT, contentId)).thenReturn(true);

        assertThat(gated.visible(null, ObjectType.CONTENT, contentId)).isTrue();
        assertThat(AccessCacheScope.shared()).isFalse();
        AccessCacheScope.clear();
    }
}
