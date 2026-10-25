package com.powercms.cms.content.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.powercms.cms.content.CategoryService;
import com.powercms.cms.content.ContentService;
import com.powercms.cms.content.compose.ClassComposeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class ContentController {

    private final CategoryService catService;
    private final ContentService contentService;
    private final com.powercms.cms.content.ContentDeletionService contentDeletionService;
    private final com.powercms.cms.content.PageResourceService pageResourceService;
    private final ClassComposeService classCompose;
    private final com.powercms.cms.delivery.PageRenderService pageRenderService;
    private final com.powercms.cms.content.ContentVersionDiffService versionDiffService;

    ContentController(CategoryService catService, ContentService contentService, com.powercms.cms.content.ContentDeletionService contentDeletionService, com.powercms.cms.content.PageResourceService pageResourceService, ClassComposeService classCompose, com.powercms.cms.delivery.PageRenderService pageRenderService, com.powercms.cms.content.ContentVersionDiffService versionDiffService) {
        this.catService = catService;
        this.contentService = contentService;
        this.contentDeletionService = contentDeletionService;
        this.pageResourceService = pageResourceService;
        this.classCompose = classCompose;
        this.pageRenderService = pageRenderService;
        this.versionDiffService = versionDiffService;
    }

    // ==== 分类 ====

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/categories")
    List<CategoryService.CategoryInfo> categories(@PathVariable UUID siteId) {
        return catService.tree(siteId);
    }

    record CreateCatReq(@NotBlank String name, String pathAlias, UUID parentId, Integer sortOrder) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PostMapping("/sites/{siteId}/categories")
    ResponseEntity<CategoryService.CategoryInfo> createCategory(@PathVariable UUID siteId,
                                                                  @Valid @RequestBody CreateCatReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(catService.create(siteId, req.parentId(), req.name(), req.pathAlias(), req.sortOrder()));
    }

    record UpdateCatReq(String name, String pathAlias, Integer sortOrder) {}

    @PreAuthorize("@siteAuthorization.category(authentication, #id, 'write')")
    @PutMapping("/categories/{id}")
    CategoryService.CategoryInfo updateCategory(@PathVariable UUID id, @RequestBody UpdateCatReq req) {
        return catService.update(id, req.name(), req.pathAlias(), req.sortOrder());
    }

    record MoveCatReq(UUID targetParentId, Integer sortOrder) {}

    @PreAuthorize("@siteAuthorization.category(authentication, #id, 'write')")
    @PostMapping("/categories/{id}/move")
    CategoryService.CategoryInfo moveCategory(@PathVariable UUID id, @RequestBody MoveCatReq req) {
        return catService.move(id, req.targetParentId(), req.sortOrder());
    }

    record ReorderCatsReq(UUID parentId, @NotNull List<UUID> orderedIds) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PutMapping("/sites/{siteId}/categories/reorder")
    ResponseEntity<Void> reorderCategories(@PathVariable UUID siteId, @Valid @RequestBody ReorderCatsReq req) {
        catService.reorder(siteId, req.parentId(), req.orderedIds());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.category(authentication, #id, 'write')")
    @DeleteMapping("/categories/{id}")
    ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        catService.delete(id); return ResponseEntity.noContent().build();
    }

    // ==== 分类号 compose 试运行 ====

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @PostMapping("/sites/{siteId}/class-compose/try")
    Map<String, Object> tryClassCompose(@PathVariable UUID siteId, @RequestBody JsonNode body) {
        return classCompose.tryCompose(siteId, body);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/pages/tree")
    List<com.powercms.cms.delivery.PageRenderService.PageInfo> pages(@PathVariable UUID siteId) {
        return pageRenderService.listPages(siteId);
    }

    record UpdatePageResourceReq(@NotNull Integer expectedVersion, String title,
                                 @NotBlank String routePath, String templatePath, String fieldValues, String note,
                                 boolean createRedirectDraft, boolean withdrawPublished) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write') and " +
        "(!#req.createRedirectDraft() or @siteAuthorization.check(authentication, #siteId, 'site-admin'))")
    @PatchMapping("/sites/{siteId}/pages/{pageId}")
    com.powercms.cms.content.PageResourceService.UpdateResult updatePageResource(
        @PathVariable UUID siteId, @PathVariable UUID pageId,
        @Valid @RequestBody UpdatePageResourceReq req,
        org.springframework.security.core.Authentication authentication) {
        return pageResourceService.update(siteId, pageId, req.expectedVersion(), req.title(),
            req.routePath(), req.templatePath(), req.fieldValues(), req.note(), req.createRedirectDraft(),
            req.withdrawPublished(), authentication.getName());
    }

    record MovePageSubtreeReq(@NotBlank String sourcePath, @NotBlank String targetPath,
                              @NotNull List<com.powercms.cms.content.PageResourceService.ExpectedPage> expectedPages,
                              boolean createRedirectDrafts) {}

    record PageRouteImpactReq(@NotNull List<com.powercms.cms.content.PageRouteImpactService.RouteChange> changes) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @PostMapping("/sites/{siteId}/pages/impact")
    com.powercms.cms.content.PageRouteImpactService.Report pageRouteImpact(
        @PathVariable UUID siteId, @Valid @RequestBody PageRouteImpactReq req) {
        return pageResourceService.impact(siteId, req.changes());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @PostMapping("/sites/{siteId}/pages/redirects/preview")
    com.powercms.cms.content.PageMoveRedirectService.Plan pageRedirectPreview(
        @PathVariable UUID siteId, @Valid @RequestBody PageRouteImpactReq req) {
        return pageResourceService.redirectPreview(siteId, req.changes());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PostMapping("/sites/{siteId}/pages/move/preview")
    com.powercms.cms.content.PageResourceService.MovePlan previewPageSubtreeMove(
        @PathVariable UUID siteId, @Valid @RequestBody MovePageSubtreeReq req) {
        return pageResourceService.previewMove(siteId, req.sourcePath(), req.targetPath(), req.expectedPages());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write') and " +
        "(!#req.createRedirectDrafts() or @siteAuthorization.check(authentication, #siteId, 'site-admin'))")
    @PostMapping("/sites/{siteId}/pages/move")
    com.powercms.cms.content.PageResourceService.MovePlan movePageSubtree(
        @PathVariable UUID siteId, @Valid @RequestBody MovePageSubtreeReq req,
        org.springframework.security.core.Authentication authentication) {
        return pageResourceService.moveSubtree(siteId, req.sourcePath(), req.targetPath(),
            req.expectedPages(), req.createRedirectDrafts(), authentication.getName());
    }
    // ==== 内容 ====

    record CreateContentReq(@NotBlank String title, UUID categoryId, UUID folderId,
                            @NotNull UUID contentTypeId, String routePath,
                            String templatePath, String fieldValues) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PostMapping("/sites/{siteId}/contents")
    ResponseEntity<ContentService.ContentInfo> createContent(@PathVariable UUID siteId,
                                                               @Valid @RequestBody CreateContentReq req,
                                                               @RequestAttribute("organizationId") UUID orgId,
                                                               org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(contentService.create(siteId, req.categoryId(), req.folderId(), req.contentTypeId(),
                req.title(), req.routePath(), req.templatePath(), req.fieldValues(), authentication.getName()));
    }

    @PreAuthorize("@siteAuthorization.category(authentication, #categoryId, 'read')")
    @GetMapping("/categories/{categoryId}/contents")
    List<ContentService.ContentInfo> listByCat(@PathVariable UUID categoryId,
                                               org.springframework.security.core.Authentication authentication) {
        return contentService.listByCategory(userIdOf(authentication), categoryId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/folders/{folderId}/contents")
    List<ContentService.ContentInfo> listByFolder(@PathVariable UUID siteId, @PathVariable UUID folderId,
                                                  org.springframework.security.core.Authentication authentication) {
        return contentService.listByFolder(userIdOf(authentication), folderId);
    }

    @PreAuthorize("@siteAuthorization.contentType(authentication, #contentTypeId, 'read')")
    @GetMapping("/sites/{siteId}/content-types/{contentTypeId}/contents")
    List<ContentService.ContentInfo> listByType(
        @PathVariable UUID siteId, @PathVariable UUID contentTypeId,
        org.springframework.security.core.Authentication authentication) {
        return contentService.listByType(userIdOf(authentication), contentTypeId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/contents/search")
    List<ContentService.ContentInfo> search(
        @PathVariable UUID siteId,
        @RequestParam(required = false) UUID contentTypeId,
        @RequestParam(required = false) List<UUID> categoryIds,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String keyword,
        org.springframework.security.core.Authentication authentication) {
        return contentService.search(userIdOf(authentication), siteId, contentTypeId, categoryIds, null, status, keyword);
    }

    private static UUID userIdOf(org.springframework.security.core.Authentication authentication) {
        return authentication != null
            && authentication.getPrincipal()
                instanceof com.powercms.cms.identity.AuthenticatedUserPrincipal principal
            ? principal.getUserId() : null;
    }

    record InlineFieldReq(@NotBlank String fieldCode, String value, int expectedVersion, String format) {}

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'write')")
    @PatchMapping("/sites/{siteId}/contents/{id}/inline-field")
    ContentService.InlineFieldResult updateInlineField(@PathVariable UUID siteId, @PathVariable UUID id,
                                                        @Valid @RequestBody InlineFieldReq req,
                                                        org.springframework.security.core.Authentication authentication) {
        return contentService.updateInlineField(siteId, id, req.fieldCode(), req.value(), req.expectedVersion(),
            req.format(), authentication.getName());
    }

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'read')")
    @GetMapping("/contents/{id}")
    ResponseEntity<ContentService.ContentInfo> getContent(@PathVariable UUID id) {
        ContentService.ContentInfo info = contentService.get(id);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        // FR-AI-GOV-007：隐式标识随 API 响应保留（响应头 + 响应体字段）
        if (info.aiGenerated()) {
            headers.set("X-Content-AI-Generated", "true");
            headers.set("X-Content-AI-Model", info.aiModel() == null ? "" : info.aiModel());
        } else {
            headers.set("X-Content-AI-Generated", "false");
        }
        return new org.springframework.http.ResponseEntity<>(info, headers,
            org.springframework.http.HttpStatus.OK);
    }

    record SaveVersionReq(String fieldValues, String note, String title,
                          String routePath, String templatePath, UUID folderId) {}

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'write')")
    @PostMapping("/contents/{id}/versions")
    ResponseEntity<ContentService.VersionInfo> saveVersion(@PathVariable UUID id,
                                                             @Valid @RequestBody SaveVersionReq req,
                                                             org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok(contentService.saveVersion(id, req.fieldValues(), req.note(), req.title(),
            req.routePath(), req.templatePath(), req.folderId(), authentication.getName()));
    }

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'read')")
    @GetMapping("/contents/{id}/versions")
    List<ContentService.VersionInfo> versions(@PathVariable UUID id) {
        return contentService.versions(id);
    }

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'read')")
    @GetMapping("/contents/{id}/versions/{version}")
    ContentService.VersionInfo getVersion(@PathVariable UUID id, @PathVariable int version) {
        return contentService.getVersion(id, version);
    }

    /**
     * FR-CV-VER-008：版本字段级对比（含条目级标题/路径/模板）。
     *
     * <p>字段名为 {@code field}：模型字段用模型字段码（前端用模型定义映射展示名），
     * 条目级字段用 {@code title} / {@code routePath} / {@code templatePath}。</p>
     */
    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'read')")
    @GetMapping("/contents/{id}/versions/diff")
    com.powercms.cms.content.ContentVersionDiffService.VersionDiff versionDiff(
            @PathVariable UUID id,
            @RequestParam int from,
            @RequestParam int to) {
        return versionDiffService.diffWithItemFields(id, from, to);
    }

    record RevertVersionReq(String note) {}

    /**
     * FR-CV-VER-004/007/012：恢复到指定版本。
     *
     * <p>采用「恢复创建新版本」语义——不覆盖历史，而是以目标版本快照创建一个新版本，并落审计。
     * 高风险操作，故要求 {@code write} 权限且前台必须二次确认。</p>
     */
    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'write')")
    @PostMapping("/contents/{id}/versions/{version}/revert")
    ResponseEntity<ContentService.VersionInfo> revertVersion(@PathVariable UUID id,
                                                              @PathVariable int version,
                                                              @RequestBody(required = false) RevertVersionReq req,
                                                              org.springframework.security.core.Authentication authentication) {
        // 备注可覆盖默认的「恢复到 v{n}」，便于说明恢复原因（FR-CV-EDIT-006 变更备注随版本记录）。
        String note = req == null ? null : req.note();
        return ResponseEntity.ok(contentService.revertToVersion(id, version, note, authentication.getName()));
    }

    record StatusReq(@NotBlank String status) {}

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'read')")
    @GetMapping("/contents/{id}/publish-checklist")
    ContentService.PublishChecklist publishChecklist(@PathVariable UUID id) {
        return contentService.publishChecklist(id);
    }

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'review')")
    @PostMapping("/contents/{id}/status")
    ResponseEntity<Void> updateStatus(@PathVariable UUID id, @Valid @RequestBody StatusReq req) {
        contentService.updateStatus(id, req.status()); return ResponseEntity.noContent().build();
    }

    /**
     * 删除已归档内容（级联清除关联数据，审计保留）。
     * 与发布/设为草稿同级（站点能力 review）：归档是删除的前置条件，能归档即可删除。
     */
    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'review')")
    @DeleteMapping("/contents/{id}")
    ResponseEntity<Void> deleteContent(@PathVariable UUID id) {
        contentDeletionService.deleteItem(id); return ResponseEntity.noContent().build();
    }

    /** 批量状态流转（提交审核/发布/设为草稿/归档）：逐条成败，部分失败不回滚。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'review')")
    @PostMapping("/sites/{siteId}/contents/batch-status")
    ResponseEntity<ContentService.BatchResult> batchStatus(@PathVariable UUID siteId, @Valid @RequestBody BatchStatusReq req) {
        return ResponseEntity.ok(contentService.batchStatus(req.ids(), req.status()));
    }

    record BatchStatusReq(@NotNull List<UUID> ids, @NotBlank String status) {}

    /** 批量删除：仅已归档内容会被删除，未归档的逐条报错返回。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'review')")
    @PostMapping("/sites/{siteId}/contents/batch-delete")
    ResponseEntity<ContentService.BatchResult> batchDelete(@PathVariable UUID siteId, @Valid @RequestBody BatchDeleteReq req) {
        return ResponseEntity.ok(contentDeletionService.deleteBatch(req.ids()));
    }

    record BatchDeleteReq(@NotNull List<UUID> ids) {}

    record BatchEpisodesReq(@NotNull UUID seasonId, int startNumber, int count, String titlePrefix) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')")
    @PostMapping("/sites/{siteId}/contents/batch-episodes")
    List<ContentService.ContentInfo> batchEpisodes(
        @PathVariable UUID siteId, @Valid @RequestBody BatchEpisodesReq req) {
        int start = req.startNumber() <= 0 ? 1 : req.startNumber();
        return contentService.batchCreateEpisodes(
            siteId, req.seasonId(), start, req.count(), req.titlePrefix(), "system");
    }
}
