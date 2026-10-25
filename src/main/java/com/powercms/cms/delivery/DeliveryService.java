package com.powercms.cms.delivery;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.identity.permission.ViewerAccessService;
import com.powercms.cms.publish.ReleaseService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 内容交付服务（FR-API-002~005）：只返回当前已部署 Release 内的已发布内容。
 * P2 直接查 DB，P3 可切换到搜索索引。
 * P1-E：列表按对象 view 过滤（AC-030）：未打破继承链的内容保持公开；
 * 打破后仅对 anonymous 授 view 的内容出现在交付 API。
 */
@Service
public class DeliveryService {

    private final ReleaseService releaseService;
    private final ContentItemRepository itemRepo;
    private final ContentVersionRepository versionRepo;
    private final ObjectPermissionService objectPermissions;
    private final ViewerAccessService viewerAccess;

    DeliveryService(ReleaseService releaseService, ContentItemRepository itemRepo,
                    ContentVersionRepository versionRepo,
                    ObjectPermissionService objectPermissions,
                    ViewerAccessService viewerAccess) {
        this.releaseService = releaseService;
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.objectPermissions = objectPermissions;
        this.viewerAccess = viewerAccess;
    }

    public record DeliveryItem(UUID id, String title, UUID categoryId, UUID contentTypeId,
                                int version, String fieldValues, java.time.Instant updatedAt) {}

    /** 交付视图：内容字段。社区版不含媒资中台摘要，{@code media} 恒为空列表以保持契约稳定。 */
    public record DeliveryView(UUID id, String title, UUID categoryId, UUID contentTypeId,
                               int version, String fieldValues, java.time.Instant updatedAt,
                               List<java.util.Map<String, Object>> media) {}

    private DeliveryView toView(UUID siteId, DeliveryItem d) {
        return new DeliveryView(d.id(), d.title(), d.categoryId(), d.contentTypeId(), d.version(),
            d.fieldValues(), d.updatedAt(), List.of());
    }

    public List<DeliveryView> listPublishedViews(UUID siteId, UUID categoryId, int page, int size) {
        return listPublished(siteId, categoryId, page, size).stream()
            .map(d -> toView(siteId, d))
            .toList();
    }

    public DeliveryView getViewById(UUID siteId, UUID contentId) {
        DeliveryItem d = getById(siteId, contentId);
        return toView(siteId, d);
    }

    public List<DeliveryItem> listPublished(UUID siteId, UUID categoryId, int page, int size) {
        var rel = releaseService.getDeployed(siteId);
        if (rel == null) return List.of();

        var contents = releaseService.getContents(rel.id());
        return contents.stream()
            .map(rc -> {
                var item = itemRepo.findById(rc.contentItemId()).orElse(null);
                if (item == null || !"PUBLISHED".equals(item.getStatus())) return null;
                var ver = versionRepo.findByContentItemIdAndVersion(rc.contentItemId(), rc.version()).orElse(null);
                return new DeliveryItem(item.getId(), item.getTitle(), item.getCategoryId(),
                    item.getContentTypeId(), rc.version(),
                    ver != null ? ver.getFieldValues() : "{}",
                    item.getUpdatedAt());
            })
            .filter(d -> d != null && (categoryId == null || categoryId.equals(d.categoryId())))
            .filter(d -> viewerAccess.visible(currentViewerId(), ObjectType.CONTENT, d.id()))
            .skip((long) page * size).limit(size)
            .toList();
    }

    public DeliveryItem getById(UUID siteId, UUID contentId) {
        var rel = releaseService.getDeployed(siteId);
        if (rel == null) throw new BusinessException(ErrorCodes.NOT_FOUND, "无已部署内容");

        var contents = releaseService.getContents(rel.id());
        var rc = contents.stream().filter(c -> c.contentItemId().equals(contentId)).findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容未在已部署 Release 中"));
        if (!viewerAccess.visible(currentViewerId(), ObjectType.CONTENT, contentId)) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在");
        }
        var item = itemRepo.findById(rc.contentItemId()).orElseThrow();
        if (!"PUBLISHED".equals(item.getStatus())) throw new BusinessException(ErrorCodes.NOT_FOUND, "\u5185\u5bb9\u672a\u53d1\u5e03");
        var ver = versionRepo.findByContentItemIdAndVersion(rc.contentItemId(), rc.version()).orElseThrow();
        return new DeliveryItem(item.getId(), item.getTitle(), item.getCategoryId(),
            item.getContentTypeId(), rc.version(), ver.getFieldValues(), item.getUpdatedAt());
    }

    private static UUID currentViewerId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }
}
