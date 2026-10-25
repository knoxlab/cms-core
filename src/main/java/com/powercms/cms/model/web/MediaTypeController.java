package com.powercms.cms.model.web;

import com.powercms.cms.model.ContentTypeService;
import com.powercms.cms.model.MediaClassifyService;
import com.powercms.cms.model.MediaTypeSeedService;
import com.powercms.cms.model.dto.ContentTypeResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 媒体内容类型管理（FR-CM-MEDIA-002~006/040/041/050/051）。
 */
@RestController
@RequestMapping({"/api/v1/sites/{siteId}/media-types", "/api/admin/sites/{siteId}/media-types"})
class MediaTypeController {

    private final ContentTypeService service;
    private final MediaClassifyService classify;

    MediaTypeController(ContentTypeService service, MediaClassifyService classify) {
        this.service = service;
        this.classify = classify;
    }

    /** 列出媒体内容类型（预置 + 自定义，含 kind/accept/storagePathPattern）。 */
    @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
    @GetMapping
    List<ContentTypeResponse> list(@PathVariable UUID siteId) {
        return service.mediaTypes(siteId);
    }

    /** 自动归类预检（FR-CM-MEDIA-041/051）：输入 MIME/文件名/大小，返回媒体类型与目标路径。 */
    record ResolveReq(String filename, String mimeType, @Positive long size) {}

    @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
    @PostMapping("/resolve")
    MediaClassifyService.Classification resolve(@PathVariable UUID siteId,
                                                @RequestBody ResolveReq req) {
        return classify.resolve(siteId, req.filename(), req.mimeType(), req.size());
    }

    /** 媒体类型级配置更新（accept/maxFileLength/storagePathPattern，FR-CM-MEDIA-040/050）。 */
    record MediaConfigReq(String accept, Long maxFileLength, String storagePathPattern) {}

    @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
    @PutMapping("/{id}/media-config")
    ContentTypeResponse updateMediaConfig(@PathVariable UUID siteId, @PathVariable UUID id,
                                          @RequestBody MediaConfigReq req) {
        return service.updateMediaConfig(siteId, id, req.accept(), req.maxFileLength(),
            req.storagePathPattern());
    }

    record CopyMediaTypeReq(String name, String code, String icon, String mediaKind) {}

    /** 复制媒体内容类型（FR-CM-MEDIA-005，沿用 FR-CM-COPY，kind 可改）。 */
    @PreAuthorize("@siteAuthorization.contentType(authentication,#id,'model')")
    @PostMapping("/{id}/copy")
    ResponseEntity<ContentTypeResponse> copy(@PathVariable UUID siteId, @PathVariable UUID id,
                                             @RequestBody(required = false) CopyMediaTypeReq req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.copy(siteId, id,
            req == null ? null : req.name(), req == null ? null : req.code(),
            req == null ? null : req.icon()));
    }

    /** 返回预置种子定义（供前端展示默认 accept/kind）。 */
    @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')")
    @GetMapping("/seeds")
    List<MediaTypeSeedService.MediaTypeSeed> seeds() {
        return MediaTypeSeedService.seeds();
    }
}
