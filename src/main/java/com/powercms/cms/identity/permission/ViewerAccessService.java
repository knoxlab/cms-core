package com.powercms.cms.identity.permission;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 交付侧主体判定（FR-ACCESS-008，R1；分类传递 FR-ACCESS-006，D5）。
 * 可见 = 匿名公开档 ∪ 当前主体权利，再与 IP、地域、时间窗求交。
 * 下载另要求**对象级总闸**打开（`program_access_policy.download_enabled`，媒体原件还会并入
 * 引用它的内容的开关、取更严一侧）**且**主体具备 download 权利；两者拒绝分别返回
 * `DOWNLOAD_DISABLED` 与 `DOWNLOAD_NOT_GRANTED`，便于区分「改策略」还是「改授权」。
 * 否则未打破继承的公开内容会在会员登录后消失。
 * 内容在未打破继承时，文件夹链与分类链求交；内容自身打破继承后只看内容 ACL。
 * 文件夹父级解析保持不变。
 */
@Service
public class ViewerAccessService {

    private final ObjectPermissionService permissions;
    private final UserRepository users;
    private final ContentItemRepository contents;
    private final ProgramAccessService programAccess;

    public ViewerAccessService(ObjectPermissionService permissions, UserRepository users) {
        this(permissions, users, null, null);
    }

    public ViewerAccessService(ObjectPermissionService permissions, UserRepository users,
                                ContentItemRepository contents) {
        this(permissions, users, contents, null);
    }

    @Autowired
    public ViewerAccessService(ObjectPermissionService permissions, UserRepository users,
                                ContentItemRepository contents, ProgramAccessService programAccess) {
        this.permissions = permissions;
        this.users = users;
        this.contents = contents;
        this.programAccess = programAccess;
    }

    public record AccessDecision(boolean allowed, String reason) {
        public static AccessDecision allow() { return new AccessDecision(true, null); }
        public static AccessDecision deny(String reason) { return new AccessDecision(false, reason); }
    }

    public boolean visible(UUID viewerUserId, String objectType, UUID objectId) {
        return decide(viewerUserId, objectType, objectId, false).allowed();
    }

    public boolean visible(UUID viewerUserId, String objectType, UUID objectId, String right) {
        if (ObjectType.DOWNLOAD.equals(right)) return downloadable(viewerUserId, objectType, objectId);
        return decide(viewerUserId, objectType, objectId, false).allowed()
            && subjectVisible(viewerUserId, objectType, objectId, right);
    }

    public boolean downloadable(UUID viewerUserId, String objectType, UUID objectId) {
        return decide(viewerUserId, objectType, objectId, true).allowed();
    }

    public AccessDecision decide(UUID viewerUserId, String objectType, UUID objectId, boolean download) {
        if (!subjectVisible(viewerUserId, objectType, objectId, ObjectType.VIEW)) {
            return AccessDecision.deny(com.powercms.common.error.ErrorCodes.PERMISSION_DENIED);
        }
        AccessRequestContext.Snapshot snapshot = AccessRequestContext.current();
        String clientIp = snapshot == null ? null : snapshot.clientIp();
        java.time.Instant now = snapshot == null || snapshot.now() == null ? java.time.Instant.now() : snapshot.now();
        if (programAccess != null) {
            String reason = programAccess.denyReason(objectType, objectId, clientIp, now, false);
            if (reason != null) return AccessDecision.deny(reason);
            if (programAccess.variesByRequest(objectType, objectId)) {
                AccessCacheScope.denyShared();
            }
        }
        if (!download) return AccessDecision.allow();
        // 总闸（对象策略）与名单（主体授权）分档报错：前者换谁都下不了，后者是「你不在授权名单里」，
        // 两者的处置动作完全不同（改访问策略 vs 改角色/会员组授权），共用一个码只能让人两头翻。
        boolean enabled = programAccess != null && programAccess.resolveDownload(objectType, objectId).allowed();
        if (!enabled) return AccessDecision.deny(com.powercms.common.error.ErrorCodes.DOWNLOAD_DISABLED);
        if (!subjectVisible(viewerUserId, objectType, objectId, ObjectType.DOWNLOAD)) {
            return AccessDecision.deny(com.powercms.common.error.ErrorCodes.DOWNLOAD_NOT_GRANTED);
        }
        return AccessDecision.allow();
    }

    private boolean subjectVisible(UUID viewerUserId, String objectType, UUID objectId, String right) {
        if (ObjectType.CONTENT.equals(objectType)) {
            return contentVisible(viewerUserId, objectId, right);
        }
        return union(viewerUserId, objectType, objectId, right);
    }

    private boolean contentVisible(UUID viewerUserId, UUID objectId, String right) {
        if (objectId != null && permissions.isBroken(ObjectType.CONTENT, objectId)) {
            return union(viewerUserId, ObjectType.CONTENT, objectId, right);
        }
        boolean folderChain = union(viewerUserId, ObjectType.CONTENT, objectId, right);
        UUID categoryId = categoryOf(objectId);
        if (categoryId == null) return folderChain;
        return folderChain && union(viewerUserId, ObjectType.TAXONOMY, categoryId, right);
    }

    private UUID categoryOf(UUID contentId) {
        if (contents == null || contentId == null) return null;
        return contents.findById(contentId).map(ContentItemEntity::getCategoryId).orElse(null);
    }

    private boolean union(UUID viewerUserId, String objectType, UUID objectId, String right) {
        boolean anonymous = permissions.hasAnonymousRight(objectType, objectId, right);
        if (viewerUserId == null) return anonymous;
        UserEntity user = users.findById(viewerUserId).orElse(null);
        if (user == null) return anonymous;
        return anonymous || permissions.hasRight(viewerUserId, objectType, objectId, right);
    }

    /** 只有前台会员会话才按会员档解释；后台员工调用方仍可把 userId 传入 {@link #visible}。 */
    public boolean frontendMember(UUID userId) {
        if (userId == null) return false;
        UserEntity user = users.findById(userId).orElse(null);
        return user != null && UserEntity.KIND_FRONTEND.equals(user.getKind());
    }
}
