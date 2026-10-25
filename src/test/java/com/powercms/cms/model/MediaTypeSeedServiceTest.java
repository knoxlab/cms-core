package com.powercms.cms.model;

import com.powercms.cms.model.dto.ContentTypeResponse;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.SiteCreatedEvent;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体内容类型种子（FR-CM-MEDIA-002/042）：站点激活预置六类 + 幂等。
 */
@ExtendWith(MockitoExtension.class)
class MediaTypeSeedServiceTest {

    @Mock
    private ContentTypeService typeService;
    @Mock
    private ContentTypeRepository typeRepo;
    @Mock
    private SiteRepository siteRepo;
    @Mock
    private com.powercms.cms.model.repo.ContentTypeLifecycleRepository lifecycleRepo;

    private MediaTypeSeedService service;
    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MediaTypeSeedService(typeService, typeRepo, siteRepo, lifecycleRepo);
        // 缺省：所有类型均无 WEBHOOK 钩子 → 种子会补写
        org.mockito.Mockito.lenient()
            .when(lifecycleRepo.findByContentTypeIdAndEvent(any(), eq("WEBHOOK")))
            .thenReturn(Optional.empty());
    }

    @Test
    void seedsSixPredefinedMediaTypes() {
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenReturn(false);
        // create 返回伪 response，findById 返回可写 entity，save 原样返回
        when(typeService.create(eq(siteId), any())).thenAnswer(inv -> {
            ContentTypeResponse r = org.mockito.Mockito.mock(ContentTypeResponse.class);
            UUID id = UUID.randomUUID();
            when(r.id()).thenReturn(id);
            ContentTypeEntity entity = new ContentTypeEntity();
            entity.setId(id);
            when(typeRepo.findById(id)).thenReturn(Optional.of(entity));
            return r;
        });
        when(typeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int created = service.seed(siteId);

        assertThat(created).isEqualTo(6);
        verify(typeService, times(6)).create(eq(siteId), any());
        verify(typeRepo, times(6)).save(any());
        verify(typeService, times(6)).publish(eq(siteId), any(), eq("media-type-seed"), eq(null));
    }

    @Test
    void documentAcceptIncludesMarkdownAndMergeIsIdempotent() {
        var document = MediaTypeSeedService.seeds().stream()
            .filter(s -> "media_document".equals(s.code())).findFirst().orElseThrow();
        assertThat(document.accept()).contains("text/markdown", "application/epub+zip");
        String old = "application/pdf,text/plain";
        String merged = MediaTypeSeedService.mergeDocumentAccept(old);
        assertThat(merged).startsWith(old).contains("text/markdown");
        assertThat(MediaTypeSeedService.mergeDocumentAccept(merged)).isEqualTo(merged);
    }

    @Test
    void fontSeedHasFontKindAndAccept() {
        var font = MediaTypeSeedService.seeds().stream()
            .filter(s -> "media_font".equals(s.code())).findFirst().orElseThrow();
        assertThat(font.kind()).isEqualTo("font");
        assertThat(font.accept()).isEqualTo("font/*");
        // 字体类型须在 media_other 之前，保证通配优先级之外的可读顺序
        assertThat(MediaTypeSeedService.seeds())
            .extracting(MediaTypeSeedService.MediaTypeSeed::code)
            .containsSubsequence("media_font", "media_other");
    }

    @Test
    void seedIsIdempotentWhenTypesAlreadyExist() {
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenReturn(true);
        // 已存在类型：幂等补齐系统字段布局
        ContentTypeEntity t = new ContentTypeEntity();
        t.setId(UUID.randomUUID());
        t.setCode("media_video");
        t.setMediaKind("video");
        when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(any(), any()))
            .thenReturn(List.of(t));

        int created = service.seed(siteId);

        assertThat(created).isEqualTo(0);
        verify(typeService, never()).create(any(), any());
        verify(typeRepo, never()).save(any());
        verify(typeService, times(6)).ensureSystemFieldLayout(eq(siteId), eq(t.getId()));
        // 存量类型无 WEBHOOK 钩子时幂等补写 6 个
        verify(lifecycleRepo, times(6)).save(any());
    }

    @Test
    void webhookHookNotOverwrittenWhenAlreadyPresent() {
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenReturn(true);
        ContentTypeEntity t = new ContentTypeEntity();
        t.setId(UUID.randomUUID());
        t.setCode("media_video");
        t.setMediaKind("video");
        when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(any(), any()))
            .thenReturn(List.of(t));
        // 用户已自定义 WEBHOOK 钩子：不覆盖
        when(lifecycleRepo.findByContentTypeIdAndEvent(eq(t.getId()), eq("WEBHOOK")))
            .thenReturn(Optional.of(new com.powercms.cms.model.entity.ContentTypeLifecycleEntity()));

        service.seed(siteId);

        verify(lifecycleRepo, never()).save(any());
    }

    @Test
    void legacyPresetHookUpgradedToLatestScript() {
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenReturn(true);
        ContentTypeEntity t = new ContentTypeEntity();
        t.setId(UUID.randomUUID());
        t.setCode("media_video");
        t.setMediaKind("video");
        when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(any(), any()))
            .thenReturn(List.of(t));
        // 存量钩子仍是 v1 预置脚本（未含版本标记）：升级为最新脚本
        var existing = new com.powercms.cms.model.entity.ContentTypeLifecycleEntity();
        existing.setScriptContent("""
            // WEBHOOK 钩子：媒体服务器回调后动态修改 MEDIA 内容项字段键值。
            // 入参：fields=当前字段 Map、asset={id,kind,status,filename}、event="WEBHOOK"。
            // 返回 Map 时按返回键值覆盖同名系统字段；返回 null 或非 Map 则不做修改。
            def patch = [:]
            // 1) 海报规范化：poster 为相对路径时拼媒体服务器基址（按需替换 BASE）
            if (fields.thumbnailUrl_s && fields.thumbnailUrl_s.startsWith('/')) {
              def base = 'http://knox-media:8200'
              patch.thumbnailUrl_s = base + fields.thumbnailUrl_s
              if (fields.posterUrl_s) patch.posterUrl_s = base + fields.posterUrl_s
              if (fields.coverUrl_s) patch.coverUrl_s = base + fields.coverUrl_s
            }
            // 2) 处理完成打标：便于内容检索按 coverReady_b 过滤
            if (fields.thumbnailUrl_s && !fields.coverReady_b) {
              patch.coverReady_b = 'true'
            }
            return patch.isEmpty() ? null : patch
            """);
        when(lifecycleRepo.findByContentTypeIdAndEvent(eq(t.getId()), eq("WEBHOOK")))
            .thenReturn(Optional.of(existing));
        when(lifecycleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.seed(siteId);

        verify(lifecycleRepo, times(1)).save(any());
        assertThat(existing.getScriptContent())
            .contains("webhook-preset: v3")
            .contains("metadata.obtained")
            .contains("poster.obtained");
    }

    @Test
    void userCustomizedHookScriptNotOverwritten() {
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenReturn(true);
        ContentTypeEntity t = new ContentTypeEntity();
        t.setId(UUID.randomUUID());
        t.setCode("media_video");
        t.setMediaKind("video");
        when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(any(), any()))
            .thenReturn(List.of(t));
        // 用户改写过的脚本（非预置内容）：绝不覆盖
        var existing = new com.powercms.cms.model.entity.ContentTypeLifecycleEntity();
        existing.setScriptContent("return [custom_s: 'mine']");
        when(lifecycleRepo.findByContentTypeIdAndEvent(eq(t.getId()), eq("WEBHOOK")))
            .thenReturn(Optional.of(existing));

        service.seed(siteId);

        verify(lifecycleRepo, never()).save(any());
        assertThat(existing.getScriptContent()).isEqualTo("return [custom_s: 'mine']");
    }

    @Test
    void siteCreatedEventTriggersSeed() {
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenReturn(true);
        service.onSiteCreated(new SiteCreatedEvent(siteId, UUID.randomUUID()));
        verify(typeRepo, times(6)).existsBySiteIdAndCode(eq(siteId), any());
    }

    @Test
    void seedFailureDoesNotPropagateFromEvent() {
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenThrow(new RuntimeException("db down"));
        // 事件监听内吞掉异常：种子失败不阻断站点创建
        service.onSiteCreated(new SiteCreatedEvent(siteId, UUID.randomUUID()));
    }

    @Test
    void applicationReadyBackfillsExistingSites() {
        com.powercms.cms.site.entity.SiteEntity s = new com.powercms.cms.site.entity.SiteEntity();
        s.setId(siteId);
        when(siteRepo.findAll()).thenReturn(List.of(s));
        // 站点已具备全部种子类型：补种应为 0，且不产生 create/save
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenReturn(true);

        service.onApplicationReady(null);

        verify(typeRepo, times(6)).existsBySiteIdAndCode(eq(siteId), any());
        verify(typeService, never()).create(any(), any());
        verify(typeRepo, never()).save(any());
    }

    @Test
    void applicationReadySkipsFailedSiteAndContinues() {
        com.powercms.cms.site.entity.SiteEntity a = new com.powercms.cms.site.entity.SiteEntity();
        a.setId(UUID.randomUUID());
        com.powercms.cms.site.entity.SiteEntity b = new com.powercms.cms.site.entity.SiteEntity();
        b.setId(siteId);
        when(siteRepo.findAll()).thenReturn(List.of(a, b));
        when(typeRepo.existsBySiteIdAndCode(any(), any()))
            .thenThrow(new RuntimeException("db down"))
            .thenAnswer(inv -> true);

        service.onApplicationReady(null);

        // 站点 a 首次调用即失败被吞掉，站点 b 正常补种（6 次）；共 7 次
        verify(typeRepo, times(7)).existsBySiteIdAndCode(any(), any());
    }
}
