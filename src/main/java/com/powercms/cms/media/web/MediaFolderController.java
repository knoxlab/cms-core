package com.powercms.cms.media.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.media.MediaFolderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 站点媒体目录接口（FR-AGT-MEDIA-005）：Agent 走 MCP 工具，管理台/前端复用同一领域服务。
 *
 * <p>门禁：读取需站点 {@code read}，创建/搬移需站点 {@code write}；目录级 ACL 未建模，
 * 由站点能力统一承担（与媒体库既有口径一致）。
 *
 * <p>操作者取 {@link AuditService#currentActor()}（登录用户取用户 id，与 MCP 工具链 actor 口径一致），
 * 由 audit 模块承担身份解析，避免 media 反向依赖 identity 形成环。
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/media/folders")
class MediaFolderController {

    private final MediaFolderService folders;

    MediaFolderController(MediaFolderService folders) {
        this.folders = folders;
    }

    record CreateFolderRequest(@NotBlank String name, UUID parentId, Integer sortOrder) {}

    record MoveFolderRequest(UUID parentId) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping
    List<MediaFolderService.FolderView> tree(@PathVariable UUID siteId) {
        return folders.tree(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/{folderId}")
    MediaFolderService.FolderView get(@PathVariable UUID siteId, @PathVariable UUID folderId) {
        return folders.get(siteId, folderId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PostMapping
    MediaFolderService.FolderView create(@PathVariable UUID siteId,
                                        @Valid @RequestBody CreateFolderRequest req) {
        return folders.create(siteId, req.parentId(), req.name(), req.sortOrder(), AuditService.currentActor());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PostMapping("/{folderId}/move")
    MediaFolderService.FolderView move(@PathVariable UUID siteId, @PathVariable UUID folderId,
                                      @RequestBody MoveFolderRequest req) {
        return folders.move(siteId, folderId, req.parentId(), AuditService.currentActor());
    }
}
