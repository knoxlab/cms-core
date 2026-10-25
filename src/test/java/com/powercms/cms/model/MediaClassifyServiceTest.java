package com.powercms.cms.model;

import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 媒体自动归类与路径宏解析（FR-CM-MEDIA-040~044/050/051）。
 */
@ExtendWith(MockitoExtension.class)
class MediaClassifyServiceTest {

    @Mock
    private ContentTypeRepository typeRepo;

    private MediaClassifyService service;
    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MediaClassifyService(typeRepo);
    }

    private ContentTypeEntity type(String code, String kind, String accept, Long maxLen, String pattern) {
        ContentTypeEntity t = new ContentTypeEntity();
        t.setId(UUID.randomUUID());
        t.setSiteId(siteId);
        t.setCode(code);
        t.setName(code);
        t.setMediaKind(kind);
        t.setMediaAccept(accept);
        t.setMediaMaxFileLength(maxLen);
        t.setStoragePathPattern(pattern);
        return t;
    }

    @Test
    void wildcardAcceptClassifiesPngAsImage() {
        ContentTypeEntity video = type("media_video", "video", "video/*", null, null);
        ContentTypeEntity image = type("media_image", "image", "image/*", null, null);
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(video, image));

        MediaClassifyService.Classification c =
            service.resolve(siteId, "photo.png", "image/png", 1024);

        assertThat(c.kind()).isEqualTo("image");
        assertThat(c.mediaTypeCode()).isEqualTo("media_image");
        assertThat(c.overMax()).isFalse();
    }

    @Test
    void exactMimeBeatsWildcardAndBare() {
        ContentTypeEntity exact = type("media_svg", "image", "image/svg+xml", null, null);
        ContentTypeEntity wildcard = type("media_image", "image", "image/*", null, null);
        ContentTypeEntity bare = type("media_other", "other", "*/*", null, null);
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(exact, wildcard, bare));

        // image/svg+xml 应命中精确 media_svg，而非通配 media_image
        MediaClassifyService.Classification c =
            service.resolve(siteId, "logo.svg", "image/svg+xml", 10);
        assertThat(c.mediaTypeCode()).isEqualTo("media_svg");
        // 通配 image/* 应命中 media_image，而非 */* 的 media_other
        MediaClassifyService.Classification c2 =
            service.resolve(siteId, "photo.png", "image/png", 10);
        assertThat(c2.mediaTypeCode()).isEqualTo("media_image");
    }

    @Test
    void fallbackToExtensionWhenDeclaredMimeIsOctetStream() {
        ContentTypeEntity image = type("media_image", "image", "image/*", null, null);
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(image));

        MediaClassifyService.Classification c =
            service.resolve(siteId, "photo.jpg", "application/octet-stream", 1024);
        assertThat(c.kind()).isEqualTo("image");
    }

    @Test
    void overMaxFlagsFileTooLarge() {
        ContentTypeEntity video = type("media_video", "video", "video/*", 100L, null);
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(video));

        MediaClassifyService.Classification c =
            service.resolve(siteId, "movie.mp4", "video/mp4", 200);
        assertThat(c.overMax()).isTrue();
        assertThat(c.maxFileLength()).isEqualTo(100L);
    }

    @Test
    void noMatchingTypeAndNoOtherThrowsMediaTypeNotFound() {
        ContentTypeEntity audio = type("media_audio", "audio", "audio/*", null, null);
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(audio));

        assertThatThrownBy(() -> service.resolve(siteId, "photo.png", "image/png", 10))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.MEDIA_TYPE_NOT_FOUND);
    }

    @Test
    void resolvePathExpandsAllMacros() {
        UUID assetId = UUID.randomUUID();
        String path = service.resolvePath(
            "/media/{kind}/{yyyy}/{mm}/{dd}/{objectId}/{siteId}", "video", siteId, assetId);

        assertThat(path).startsWith("/media/video/");
        assertThat(path).contains(assetId.toString());
        assertThat(path).endsWith("/" + siteId);
        assertThat(path).doesNotContain("{");
    }

    @Test
    void resolvePathRejectsUnknownMacros() {
        assertThatThrownBy(() -> service.resolvePath(
            "/media/{user}/file", "video", siteId, UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }

    @Test
    void validateAcceptRejectsIllegalToken() {
        assertThatThrownBy(() -> MediaClassifyService.validateAccept("image/png,not-a-mime"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.MEDIA_ACCEPT_INVALID);
        MediaClassifyService.validateAccept("image/*, application/pdf"); // 合法不抛
    }

    @Test
    void validateStoragePatternRejectsUnknownMacros() {
        assertThatThrownBy(() -> MediaClassifyService.validateStoragePattern("/{bucket}/{yyyy}"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }

    @Test
    void effectiveMimePrefersDeclaredOverExtension() {
        assertThat(MediaClassifyService.effectiveMime("a.jpg", "image/png")).isEqualTo("image/png");
        assertThat(MediaClassifyService.effectiveMime("a.jpg", null)).isEqualTo("image/jpeg");
        assertThat(MediaClassifyService.effectiveMime("a", "application/octet-stream"))
            .isEqualTo("application/octet-stream");
        assertThat(MediaClassifyService.effectiveMime("doc.pdf", null)).isEqualTo("application/pdf");
    }

    @Test
    void effectiveMimeOverridesGenericDeclaredMimeForKnownExtension() {
        // Windows MIME 误报回归：ts 被浏览器探测成 text/plain，必须按扩展名归回 video/mp2t
        assertThat(MediaClassifyService.effectiveMime("北京体育卫视.ts", "text/plain"))
            .isEqualTo("video/mp2t");
        assertThat(MediaClassifyService.effectiveMime("clip.m2ts", "text/plain"))
            .isEqualTo("video/mp2t");
        assertThat(MediaClassifyService.effectiveMime("video.avi", "application/octet-stream"))
            .isEqualTo("video/x-msvideo");
        assertThat(MediaClassifyService.effectiveMime("song.mp3", "text/plain"))
            .isEqualTo("audio/mpeg");
        // 鐪熷疄鏂囨湰鏂囦欢涓嶅彈褰卞搷
        assertThat(MediaClassifyService.effectiveMime("readme.txt", "text/plain"))
            .isEqualTo("text/plain");
    }

    @Test
    void tsFileClassifiesAsVideoEvenWithTextPlainMime() {
        ContentTypeEntity video = type("media_video", "video", "video/*", null, null);
        ContentTypeEntity document = type("media_document", "document",
            "application/pdf,application/rtf,text/plain", null, null);
        ContentTypeEntity other = type("media_other", "other", "*/*", null, null);
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(video, document, other));

        MediaClassifyService.Classification c =
            service.resolve(siteId, "北京体育卫视.ts", "text/plain", 1024);

        assertThat(c.kind()).isEqualTo("video");
        assertThat(c.mediaTypeCode()).isEqualTo("media_video");
    }

    @Test
    void matchLevelRanksExactOverWildcardOverBare() {
        assertThat(MediaClassifyService.matchLevel("image/png,image/jpeg", "image/png")).isEqualTo(0);
        assertThat(MediaClassifyService.matchLevel("image/*", "image/png")).isEqualTo(1);
        assertThat(MediaClassifyService.matchLevel("image/*", "video/mp4")).isEqualTo(-1);
        assertThat(MediaClassifyService.matchLevel("*/*", "application/pdf")).isEqualTo(2);
        assertThat(MediaClassifyService.matchLevel(null, "application/pdf")).isEqualTo(2);
    }

    // ---- 上传基础安全校验（FR-KNX-010）----

    @Test
    void validateUploadAllowedAcceptsKnownExtension() {
        assertThat(MediaClassifyService.validateUploadAllowed("a.mp4", "video/mp4", "image,audio,video,document"))
            .isEqualTo("video/mp4");
        assertThat(MediaClassifyService.validateUploadAllowed("a.ts", "text/plain", "image,audio,video,document"))
            .isEqualTo("video/mp2t");
        assertThat(MediaClassifyService.validateUploadAllowed("a.pdf", "application/pdf", "image,audio,video,document"))
            .isEqualTo("application/pdf");
    }

    @Test
    void validateUploadAllowedRejectsSpoofedExtension() {
        assertThatThrownBy(() -> MediaClassifyService.validateUploadAllowed(
                "evil.jpg", "application/x-php", "image,audio,video,document"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不一致");
    }

    @Test
    void validateUploadAllowedRejectsUnknownExtensionWithoutMime() {
        assertThatThrownBy(() -> MediaClassifyService.validateUploadAllowed(
                "payload.bin", null, "image,audio,video,document"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无法识别文件类型");
        assertThatThrownBy(() -> MediaClassifyService.validateUploadAllowed(
                "payload.bin", "application/octet-stream", "image,audio,video,document"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无法识别文件类型");
    }

    @Test
    void validateUploadAllowedRejectsRestrictedGroup() {
        // mkv 属于 video 组；白名单排除 video 时拒绝
        assertThatThrownBy(() -> MediaClassifyService.validateUploadAllowed(
                "a.mkv", "video/x-matroska", "image,audio"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不在允许上传范围内");
        assertThatThrownBy(() -> MediaClassifyService.validateUploadAllowed(
                "a.pdf", "application/pdf", "image,audio,video"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不在允许上传范围内");
    }

    @Test
    void validateUploadAllowedAllowsUnknownExtensionWithExplicitMime() {
        assertThat(MediaClassifyService.validateUploadAllowed("cover.bmp", "image/bmp", "image,audio,video,document"))
            .isEqualTo("image/bmp");
    }

    // ---- 字体（回归 2026-09-12：WorkBuddy 上传主题包字体被拒）----

    /**
     * 回归：主题包自托管字体曾因「扩展名不在白名单 + font 不在允许大类」被拒
     * （报「声明 MIME 类型不在允许上传范围内: font/ttf」）。字体是正常的 Web 资产，
     * 必须能按扩展名归类，且占位 MIME（octet-stream/text-plain）也应放行。
     */
    @Test
    void validateUploadAllowedAcceptsFontExtensions() {
        String groups = "image,audio,video,document,font";
        assertThat(MediaClassifyService.validateUploadAllowed("ysbt.ttf", "font/ttf", groups))
            .isEqualTo("font/ttf");
        assertThat(MediaClassifyService.validateUploadAllowed("DIN-Bold.otf", "font/otf", groups))
            .isEqualTo("font/otf");
        assertThat(MediaClassifyService.validateUploadAllowed("brand.woff2", "font/woff2", groups))
            .isEqualTo("font/woff2");
        // Windows/浏览器常见占位 MIME：按扩展名归回字体组
        assertThat(MediaClassifyService.validateUploadAllowed(
                "ysbt.ttf", "application/octet-stream", groups))
            .isEqualTo("font/ttf");
        assertThat(MediaClassifyService.validateUploadAllowed("ysbt.ttf", "text/plain", groups))
            .isEqualTo("font/ttf");
    }

    /** 未开启 font 大类的站点仍拒绝字体（白名单语义不回退）。 */
    @Test
    void validateUploadAllowedRejectsFontWhenGroupNotEnabled() {
        assertThatThrownBy(() -> MediaClassifyService.validateUploadAllowed(
                "ysbt.ttf", "font/ttf", "image,audio,video,document"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不在允许上传范围内");
    }

    /** 字体扩展名同样受伪装校验保护：.ttf 声明成非字体大类即拒绝。 */
    @Test
    void validateUploadAllowedRejectsSpoofedFontExtension() {
        assertThatThrownBy(() -> MediaClassifyService.validateUploadAllowed(
                "evil.ttf", "image/png", "image,audio,video,document,font"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不一致");
    }

    /** eot 无 font/* 注册型，不入扩展名表（须靠明确声明 MIME 走 document 组放行）。 */
    @Test
    void fontExtensionTableExcludesEot() {
        assertThat(MediaClassifyService.extensionMimeOf("ysbt.ttf")).isEqualTo("font/ttf");
        assertThat(MediaClassifyService.extensionMimeOf("legacy.eot")).isNull();
    }

    /** font/* 归为 font 大类（不被并入 document），document 不吞字体。 */
    @Test
    void mimeGroupKeepsFontSeparateFromDocument() {
        assertThat(MediaClassifyService.mimeGroup("font/ttf")).isEqualTo("font");
        assertThat(MediaClassifyService.mimeGroup("font/woff2")).isEqualTo("font");
        assertThat(MediaClassifyService.mimeGroup("application/pdf")).isEqualTo("document");
    }

    /**
     * 字体归类：站点按第六类种子建 accept=font/* 的类型（media_kind=font）时优先命中，
     * 优先于 media_other 的任意通配；未建该类型时退回 media_other。声明 MIME 为占位
     * octet-stream 时也须按扩展名归到字体类型。
     */
    @Test
    void fontClassifiesIntoFontAcceptTypeOverMediaOther() {
        ContentTypeEntity other = type("media_other", "other", "*/*", null, null);
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(other));

        MediaClassifyService.Classification fallback =
            service.resolve(siteId, "ysbt.ttf", "font/ttf", 10);
        assertThat(fallback.mediaTypeCode()).isEqualTo("media_other");
        assertThat(fallback.targetPath()).contains("/static-assets/other/");

        ContentTypeEntity font = type("media_font", "font", "font/*", 64L, null);
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(font, other));

        MediaClassifyService.Classification picked =
            service.resolve(siteId, "DIN-Bold.otf", "application/octet-stream", 10);
        assertThat(picked.mediaTypeCode()).isEqualTo("media_font");
        assertThat(picked.kind()).isEqualTo("font");
        assertThat(picked.targetPath()).contains("/static-assets/font/");
    }

    /** 种子口径的整体归类：字体落 media_font，其他既有类型不受影响。 */
    @Test
    void seededTypesClassifyFontToMediaFont() {
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(
                type("media_video", "video", "video/*", null, null),
                type("media_audio", "audio", "audio/*", null, null),
                type("media_image", "image", "image/*", null, null),
                type("media_document", "document", "application/pdf", null, null),
                type("media_font", "font", "font/*", null, null),
                type("media_other", "other", "*/*", null, null)));

        assertThat(service.resolve(siteId, "ysbt.ttf", "application/octet-stream", 10).mediaTypeCode())
            .isEqualTo("media_font");
        assertThat(service.resolve(siteId, "brand.woff2", "font/woff2", 10).mediaTypeCode())
            .isEqualTo("media_font");
        assertThat(service.resolve(siteId, "photo.png", "image/png", 10).mediaTypeCode())
            .isEqualTo("media_image");
    }
}
