package com.powercms.cms.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.compose.ClassComposeService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.ActorNameResolver;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.outbox.OutboxService;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P1-E：内容列表按对象 view 过滤（AC-007 / NFR-IAM-006）。 */
class ContentServiceSearchFilterTest {

    private ContentItemRepository itemRepo;
    private ObjectPermissionService perms;
    private com.powercms.cms.model.repo.ContentTypeRepository ctRepo;
    private ContentService service;

    private final UUID site = UUID.randomUUID();
    private final UUID visible = UUID.randomUUID();
    private final UUID hidden = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        itemRepo = mock(ContentItemRepository.class);
        perms = mock(ObjectPermissionService.class);
        ctRepo = mock(com.powercms.cms.model.repo.ContentTypeRepository.class);
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of());
        service = new ContentService(itemRepo, mock(CategoryRepository.class),
            mock(ContentVersionRepository.class), ctRepo,
            mock(FieldDefinitionRepository.class), mock(SiteFolderRepository.class),
            mock(ClassComposeService.class), null, new ObjectMapper(),
            mock(OutboxService.class), mock(ApplicationEventPublisher.class),
            perms, mock(com.powercms.cms.audit.AuditService.class),
            mock(AiDirectPublishGate.class), ActorNameResolver.passThrough(), null);

        ContentItemEntity a = new ContentItemEntity();
        a.setId(visible); a.setSiteId(site); a.setTitle("A");
        ContentItemEntity b = new ContentItemEntity();
        b.setId(hidden); b.setSiteId(site); b.setTitle("B");
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(a, b));

        // viewer 对 visible 有 view，对 hidden 无 view
        when(perms.hasRight(eq(viewer), eq(ObjectType.CONTENT), eq(visible), eq(ObjectType.VIEW))).thenReturn(true);
        when(perms.hasRight(eq(viewer), eq(ObjectType.CONTENT), eq(hidden), eq(ObjectType.VIEW))).thenReturn(false);
    }

    @Test
    void searchFiltersOutItemsWithoutViewRight() {
        var result = service.search(viewer, site, null, null, null, null, null);
        assertThat(result).extracting(ContentService.ContentInfo::id).containsExactly(visible);
    }

    @Test
    void systemContextSkipsFiltering() {
        when(perms.hasRight(any(), any(), any(), any())).thenThrow(new AssertionError("系统上下文不应走权限判定"));
        var result = service.search(null, site, null, null, null, null, null);
        assertThat(result).extracting(ContentService.ContentInfo::id).containsExactlyInAnyOrder(visible, hidden);
    }

    @Test
    void searchExcludesMediaAndSystemTypes() {
        var mediaType = new com.powercms.cms.model.entity.ContentTypeEntity();
        mediaType.setId(UUID.randomUUID()); mediaType.setObjectType("MEDIA");
        var systemType = new com.powercms.cms.model.entity.ContentTypeEntity();
        systemType.setId(UUID.randomUUID()); systemType.setSystem(true);
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of(mediaType, systemType));

        ContentItemEntity mediaItem = new ContentItemEntity();
        mediaItem.setId(UUID.randomUUID()); mediaItem.setSiteId(site);
        mediaItem.setContentTypeId(mediaType.getId()); mediaItem.setTitle("媒体影子");
        ContentItemEntity profileItem = new ContentItemEntity();
        profileItem.setId(UUID.randomUUID()); profileItem.setSiteId(site);
        profileItem.setContentTypeId(systemType.getId()); profileItem.setTitle("用户资料");

        when(itemRepo.findBySiteId(site)).thenReturn(List.of(
            itemOf(visible, "普通内容"), mediaItem, profileItem));

        var result = service.search(null, site, null, null, null, null, null);
        assertThat(result).extracting(ContentService.ContentInfo::id).containsExactly(visible);
    }

    @Test
    void explicitTypeIdRetainsHiddenTypeResults() {
        var mediaType = new com.powercms.cms.model.entity.ContentTypeEntity();
        mediaType.setId(UUID.randomUUID()); mediaType.setObjectType("MEDIA");
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of(mediaType));

        ContentItemEntity mediaItem = new ContentItemEntity();
        mediaItem.setId(UUID.randomUUID()); mediaItem.setSiteId(site);
        mediaItem.setContentTypeId(mediaType.getId()); mediaItem.setTitle("媒体影子");

        when(itemRepo.findBySiteId(site)).thenReturn(List.of(mediaItem));

        var result = service.search(null, site, mediaType.getId(), null, null, null, null);
        assertThat(result).extracting(ContentService.ContentInfo::id).containsExactly(mediaItem.getId());
    }

    private ContentItemEntity itemOf(UUID id, String title) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(id); item.setSiteId(site); item.setTitle(title);
        return item;
    }

    // ======================================================================
    // OBS-11-04：条目级创建者展示名解析
    // ======================================================================

    /** AI 批量建稿写入的 {@code created_by} 是用户 ID，检索列表下发时解析为用户名（一次批量查库）。 */
    @Test
    void searchResolvesActorUserIdToUsername() {
        var userRepo = mock(com.powercms.cms.identity.repo.UserRepository.class);
        var actorId = UUID.randomUUID();
        var user = new com.powercms.cms.identity.entity.UserEntity();
        user.setId(actorId);
        user.setUsername("admin");
        when(userRepo.findAllById(any())).thenReturn(List.of(user));
        var resolverService = serviceWith(userRepo);

        ContentItemEntity item = itemOf(visible, "AI 批量建稿产物");
        item.setCreatedBy(actorId.toString());
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(item));

        var result = resolverService.search(null, site, null, null, null, null, null);

        assertThat(result).singleElement()
            .satisfies(i -> {
                assertThat(i.createdBy()).isEqualTo(actorId.toString());
                assertThat(i.createdByName()).isEqualTo("admin");
            });
    }

    /** actor 本身是用户名时不查身份库（保持零成本）。 */
    @Test
    void searchKeepsUsernameActorUnresolved() {
        var userRepo = mock(com.powercms.cms.identity.repo.UserRepository.class);
        var resolverService = serviceWith(userRepo);

        ContentItemEntity item = itemOf(visible, "人工创建");
        item.setCreatedBy("editor.s1");
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(item));

        var result = resolverService.search(null, site, null, null, null, null, null);

        assertThat(result).singleElement()
            .satisfies(i -> assertThat(i.createdByName()).isEqualTo("editor.s1"));
        org.mockito.Mockito.verify(userRepo, org.mockito.Mockito.never()).findAllById(any());
    }

    private ContentService serviceWith(com.powercms.cms.identity.repo.UserRepository userRepo) {
        return new ContentService(itemRepo, mock(CategoryRepository.class),
            mock(ContentVersionRepository.class), ctRepo,
            mock(FieldDefinitionRepository.class), mock(SiteFolderRepository.class),
            mock(ClassComposeService.class), null, new ObjectMapper(),
            mock(OutboxService.class), mock(ApplicationEventPublisher.class),
            perms, mock(com.powercms.cms.audit.AuditService.class),
            mock(AiDirectPublishGate.class), new ActorNameResolver(userRepo), null);
    }
}
