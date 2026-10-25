package com.powercms.cms.model;

import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.SiteCreatedEvent;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * 媒体内容类型种子（FR-CM-MEDIA-002/042）：站点激活后预置六类媒体类型。
 *
 * <p>每类设置 media_kind、默认 accept 与默认存储路径模式；系统字段（含 kind 专属字段）
 * 由 {@link ContentTypeService#create} 的 seedSystemFields 按 objectType=MEDIA + mediaKind 生成。
 * 幂等：已存在的 code 直接跳过，不覆盖站点后续的自定义调整。
 *
 * <p>触发时机：新建站点（{@link SiteCreatedEvent}）与应用启动（{@link ApplicationReadyEvent}
 * 全量补种存量站点，保证本功能上线前创建的站点也能获得预置媒体类型）。
 */
@Component
public class MediaTypeSeedService {

    private static final Logger log = LoggerFactory.getLogger(MediaTypeSeedService.class);

    public static final String DEFAULT_STORAGE_PATTERN = "/static-assets/{kind}/{yyyy}/{mm}/{dd}/{objectId}";

    private final ContentTypeService typeService;
    private final ContentTypeRepository typeRepo;
    private final SiteRepository siteRepo;
    private final com.powercms.cms.model.repo.ContentTypeLifecycleRepository lifecycleRepo;

    MediaTypeSeedService(ContentTypeService typeService, ContentTypeRepository typeRepo, SiteRepository siteRepo,
                         com.powercms.cms.model.repo.ContentTypeLifecycleRepository lifecycleRepo) {
      this.typeService = typeService;
      this.typeRepo = typeRepo;
      this.siteRepo = siteRepo;
      this.lifecycleRepo = lifecycleRepo;
    }

    /**
     * 文档类型 MIME 白名单（FR-CM-MEDIA-042 / FR-DR-030）。
     *
     * <p>Markdown、HTML、CSV、EPUB、JSON/XML 与旧版 PowerPoint、ODF 必须落在
     * {@code media_document}，否则会掉进 {@code media_other}（任意类型通配），
     * 媒体库把安装手册显示成「其他」。
     */
    static final String DOCUMENT_ACCEPT =
        "application/pdf,application/rtf,text/plain,text/markdown,text/html,text/csv,"
            + "application/json,application/xml,text/xml,application/epub+zip,"
            + "application/msword,application/vnd.ms-excel,application/vnd.ms-powerpoint,"
            + "application/vnd.openxmlformats-officedocument.*,"
            + "application/vnd.oasis.opendocument.text,"
            + "application/vnd.oasis.opendocument.spreadsheet,"
            + "application/vnd.oasis.opendocument.presentation";

    /** 种子定义：code、名称、说明、media_kind、默认 accept（FR-CM-MEDIA-042）。 */
    public record MediaTypeSeed(String code, String name, String description, String kind,
                                String accept, Long maxFileLength) {}

    public static List<MediaTypeSeed> seeds() {
        return List.of(
            new MediaTypeSeed("media_video", "视频", "视频媒体资产（MP4/WebM/MOV 等）", "video", "video/*", 4L * 1024 * 1024 * 1024),
            new MediaTypeSeed("media_audio", "音频", "音频媒体资产（MP3/WAV/AAC 等）", "audio", "audio/*", 1L * 1024 * 1024 * 1024),
            new MediaTypeSeed("media_image", "图片", "图片媒体资产（JPEG/PNG/WebP 等）", "image", "image/*", 256L * 1024 * 1024),
            new MediaTypeSeed("media_document", "文档", "文档媒体资产（PDF/Office/纯文本等）", "document",
                DOCUMENT_ACCEPT,
                512L * 1024 * 1024),
            // 字体（2026-09-12 新增第六类）：主题包/片段模板自托管 Web 字体（ttf/otf/woff/woff2）。
            // accept=font/* 为通配（1 级），优先于 media_other 的任意通配（2 级）命中。
            new MediaTypeSeed("media_font", "字体", "字体媒体资产（TTF/OTF/WOFF/WOFF2 等）", "font", "font/*", 64L * 1024 * 1024),
            new MediaTypeSeed("media_other", "其他", "其他未归类的媒体资产", "other", "*/*", 4L * 1024 * 1024 * 1024));
    }

    /**
     * 站点创建后种子（FR-CM-MEDIA-002）。
     *
     * <p>必须在独立事务（REQUIRES_NEW）中执行：AFTER_COMMIT 回调触发时，站点创建事务的
     * JPA 资源仍绑定当前线程，REQUIRED 传播会加入已提交事务导致种子写入不落库。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSiteCreated(SiteCreatedEvent event) {
        try {
            seed(event.siteId());
        } catch (Exception ex) {
            // 种子失败不阻断站点创建；下次可手工触发或重建站点时补齐
            log.warn("media type seed failed site={} err={}", event.siteId(), ex.getMessage());
        }
    }

    /** 应用启动补种：为存量站点补齐预置媒体类型（幂等，任一站点失败不影响启动）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        for (SiteEntity site : siteRepo.findAll()) {
            try {
                int created = seed(site.getId());
                if (created > 0) {
                    log.info("media types backfilled site={} created={}", site.getId(), created);
                }
            } catch (Exception ex) {
                log.warn("media type backfill failed site={} err={}", site.getId(), ex.getMessage());
            }
        }
    }

  /**
   * 给已有站点的 {@code media_document} 补上后加入的文档 MIME。
   *
   * <p>只追加白名单里还没有的记号，不删用户已经收窄的项。
   * 启动纠偏在归类之前调用，否则 Markdown 仍会命中 {@code media_other}。
   */
  public int upgradeDocumentAccepts() {
    int upgraded = 0;
    for (ContentTypeEntity type : typeRepo.findActiveMediaDocuments()) {
      String current = type.getMediaAccept() == null ? "" : type.getMediaAccept();
      String merged = mergeDocumentAccept(current);
      if (merged.equals(current)) continue;
      type.setMediaAccept(merged);
      typeRepo.save(type);
      upgraded++;
      log.info("media_document accept upgraded site={} id={}", type.getSiteId(), type.getId());
    }
    return upgraded;
  }

  /** 把文档白名单里缺失的 MIME 追加到现有 accept（已有记号与通配覆盖的不重复加）。 */
  static String mergeDocumentAccept(String current) {
    String base = current == null ? "" : current.trim();
    StringBuilder out = new StringBuilder(base);
    for (String token : DOCUMENT_ACCEPT.split(",")) {
      String t = token.trim();
      if (t.isEmpty()) continue;
      int level = MediaClassifyService.matchLevel(base.isEmpty() ? null : base, t);
      if (level == 0 || level == 1) continue;
      if (out.length() > 0) out.append(',');
      out.append(t);
      base = out.toString();
    }
    return out.toString();
  }

  public int seed(UUID siteId) {
    int created = 0;
    for (MediaTypeSeed seed : seeds()) {
      if (typeRepo.existsBySiteIdAndCode(siteId, seed.code())) {
        // 存量类型：幂等补齐系统字段布局（早期种子遗漏 form_layout，FR-CM-MEDIA-003）
        // 同时补齐 media_kind（V44 新增列，存量类型可能缺失）与 WEBHOOK 钩子（webhook 响应能力后补）
        typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, seed.code()).stream()
            .findFirst()
            .ifPresent(t -> {
              typeService.ensureSystemFieldLayout(siteId, t.getId());
              if (t.getMediaKind() == null || t.getMediaKind().isBlank()) {
                t.setMediaKind(seed.kind());
                typeRepo.save(t);
                log.info("media type backfilled media_kind site={} code={} kind={}", siteId, seed.code(), seed.kind());
              }
              if ("media_document".equals(t.getCode())) {
                String merged = mergeDocumentAccept(t.getMediaAccept());
                if (!merged.equals(t.getMediaAccept() == null ? "" : t.getMediaAccept())) {
                  t.setMediaAccept(merged);
                  typeRepo.save(t);
                  log.info("media_document accept upgraded site={}", siteId);
                }
              }
              seedWebhookHook(siteId, t);
            });
        continue;
      }
      CreateContentTypeRequest request =
          new CreateContentTypeRequest(
              seed.code(),
              seed.name(),
              seed.description(),
              "MEDIA",
              null,
              seedIcon(seed.kind()),
              null,
              null,
              null,
              "MEDIA",
              null,
              null,
              "NONE",
              null,
              false,
              null,
              null,
              null,
              false,
              null,
              null,
              seed.kind(),
              null);
      var response = typeService.create(siteId, request);
      ContentTypeEntity type = typeRepo.findById(response.id()).orElseThrow();
      type.setStoragePathPattern(DEFAULT_STORAGE_PATTERN);
      type.setMediaAccept(seed.accept());
      type.setMediaMaxFileLength(seed.maxFileLength());
      typeRepo.save(type);
      seedWebhookHook(siteId, type);
      typeService.publish(siteId, type.getId(), "media-type-seed", null);
      created++;
    }
    return created;
  }

  /**
   * WEBHOOK 生命周期钩子种子（FR-CM-MEDIA-012）：媒体服务器（Knox）经 webhook 回调处理产物后，
   * 按模型对字段键值做动态修改。预置脚本可处理 Knox 全量 webhook 事件（按 event 分支）：
   * 元数据类事件把 width/height/bitrate/duration/format 写入对应系统字段，
   * 封面类事件做 poster 相对路径规范化与处理完成打标；监听事件白名单缺省为空（监听全部）。
   * 站点可随时在模型编辑页改写；存量钩子若仍是预置脚本（任一历史版本）则自动升级，用户自定义脚本不覆盖。
   */
  private void seedWebhookHook(UUID siteId, ContentTypeEntity type) {
    var existing = lifecycleRepo.findByContentTypeIdAndEvent(type.getId(), "WEBHOOK").orElse(null);
    if (existing == null) {
      var hook = new com.powercms.cms.model.entity.ContentTypeLifecycleEntity();
      hook.setContentTypeId(type.getId());
      hook.setEvent("WEBHOOK");
      hook.setScriptLang("GROOVY");
      hook.setScriptContent(defaultWebhookScript());
      hook.setTimeoutMs(3000);
      hook.setEnabled(true);
      hook.setCreatedBy("media-type-seed");
      lifecycleRepo.save(hook);
      log.info("media type webhook hook seeded site={} code={}", siteId, type.getCode());
      return;
    }
    if (isPresetScript(existing.getScriptContent())) {
      existing.setScriptContent(defaultWebhookScript());
      existing.setListenEvents(defaultWebhookListenEvents());
      lifecycleRepo.save(existing);
      log.info("media type webhook hook upgraded site={} code={}", siteId, type.getCode());
    }
  }

  /** 监听事件白名单缺省：空 = 监听 Knox 全部 webhook 事件（脚本内按 event 自行分支）。 */
  static String defaultWebhookListenEvents() {
    return null;
  }

  private static final String PRESET_MARKER = "webhook-preset:";
  private static final String PRESET_VERSION = "v3";
  private static final String CURRENT_WEBHOOK_HOOK_MARKER = "// " + PRESET_MARKER + " " + PRESET_VERSION;

  /** 历史预置脚本（升级检测用）：均为种子写入的原始内容，未含版本标记。 */
  private static final List<String> LEGACY_PRESET_SCRIPTS = List.of(legacyWebhookScriptV1(), legacyWebhookScriptV2());

  private static String legacyWebhookScriptV1() {
    return """
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
        """;
  }

  private static String legacyWebhookScriptV2() {
    return """
        // WEBHOOK 钩子：媒体服务器回调后，按回调数据动态修改 MEDIA 内容项字段键值。
        // 入参：fields=当前字段 Map、media=回调媒体数据（width/height/bitrate/durationMs/format/posterPath 等）、
        //       asset={id,kind,status,filename}、event="WEBHOOK"。
        // 返回 Map 时按返回键值覆盖本模型已定义字段；返回 null 或非 Map 则不做修改。
        def patch = [:]
        // 1) 回调元数据写入对应字段：Knox 用 0/缺省表示"无"，仅正数/非空值落字段
        if (media) {
          def pos = { v -> (v instanceof Number && v > 0) ? v : null }
          def w = pos(media.width)
          def h = pos(media.height)
          def br = pos(media.bitrate)
          def dur = pos(media.durationMs)
          if (dur == null && pos(media.duration) != null) {
            dur = pos(media.duration) * 1000
          }
          if (w != null) patch.width_i = w
          if (h != null) patch.height_i = h
          if (br != null) patch.bitrate_d = br
          if (dur != null) patch.durationMs_i = dur
          if (media.format instanceof String && !media.format.isBlank()) patch.format_s = media.format
        }
        // 2) 海报规范化：poster 为相对路径时拼媒体服务器基址（按需替换 BASE）
        if (fields.thumbnailUrl_s && fields.thumbnailUrl_s.startsWith('/')) {
          def base = 'http://knox-media:8200'
          patch.thumbnailUrl_s = base + fields.thumbnailUrl_s
          if (fields.posterUrl_s) patch.posterUrl_s = base + fields.posterUrl_s
          if (fields.coverUrl_s) patch.coverUrl_s = base + fields.coverUrl_s
        }
        // 3) 处理完成打标：便于内容检索按 coverReady_b 过滤（自定义字段需先在模型定义）
        if (fields.thumbnailUrl_s && !fields.coverReady_b) {
          patch.coverReady_b = true
        }
        return patch.isEmpty() ? null : patch
        """;
  }

  /**
   * 是否为（任一历史版本的）预置脚本：带版本标记时仅当前版本视为最新不升级；
   * 无标记时与历史预置脚本精确比对（归一化后），命中即视为仍为种子脚本 → 允许升级。
   */
  private static boolean isPresetScript(String content) {
    String c = normalize(content);
    if (c.isEmpty()) return false;
    if (c.contains(PRESET_MARKER)) {
      return !c.contains(CURRENT_WEBHOOK_HOOK_MARKER);
    }
    return LEGACY_PRESET_SCRIPTS.stream().anyMatch(l -> normalize(l).equals(c));
  }

  private static String normalize(String s) {
    return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n').trim();
  }

  /**
   * 预置 WEBHOOK 脚本：处理 Knox 全量 webhook 事件（按 event 分支）——
   * 元数据类事件把回调提交的元数据写入对应字段值（width→width_i、height→height_i、bitrate→bitrate_d、
   * durationMs 或 duration 秒→毫秒→durationMs_i、format→format_s；Knox 用 0 表示"无"，仅正数/非空值落字段）；
   * 封面类事件做 poster 相对路径规范化与处理完成打标。其他事件返回 null 不修改。
   */
  public static String defaultWebhookScript() {
    return """
        // webhook-preset: v3
        // WEBHOOK 钩子：媒体服务器（Knox）回调后，按回调事件与回调数据动态修改 MEDIA 内容项字段键值。
        // 入参：fields=当前字段 Map、media=回调媒体数据（width/height/bitrate/durationMs/format/posterPath 等）、
        //       asset={id,kind,status,filename}、event=实际回调事件（metadata.obtained / poster.obtained 等，
        //       对账/补查触发时为空串）。
        // 返回 Map 时按返回键值覆盖本模型已定义字段；返回 null 或非 Map 则不做修改。
        def patch = [:]
        // 1) 元数据类事件：把回调元数据写入对应字段（Knox 用 0/缺省表示"无"，仅正数/非空值落字段）
        if (event in ['media.added', 'metadata.obtained', 'task.completed', 'pretranscode.completed', 'run.completed']) {
          if (media) {
            def pos = { v -> (v instanceof Number && v > 0) ? v : null }
            def w = pos(media.width)
            def h = pos(media.height)
            def br = pos(media.bitrate)
            def dur = pos(media.durationMs)
            if (dur == null && pos(media.duration) != null) {
              dur = pos(media.duration) * 1000
            }
            if (w != null) patch.width_i = w
            if (h != null) patch.height_i = h
            if (br != null) patch.bitrate_d = br
            if (dur != null) patch.durationMs_i = dur
            if (media.format instanceof String && !media.format.isBlank()) patch.format_s = media.format
          }
        }
        // 2) 封面类事件：poster 相对路径规范化 + 处理完成打标
        if (event in ['poster.obtained', 'thumbnail.obtained', 'preview.obtained', 'media.visible']) {
          if (fields.thumbnailUrl_s && fields.thumbnailUrl_s.startsWith('/')) {
            def base = 'http://knox-media:8200'
            patch.thumbnailUrl_s = base + fields.thumbnailUrl_s
            if (fields.posterUrl_s) patch.posterUrl_s = base + fields.posterUrl_s
            if (fields.coverUrl_s) patch.coverUrl_s = base + fields.coverUrl_s
          }
          if (fields.thumbnailUrl_s && !fields.coverReady_b) {
            patch.coverReady_b = true
          }
        }
        return patch.isEmpty() ? null : patch
        """;
  }

    private static String seedIcon(String kind) {
        return switch (kind) {
            case "video" -> "VideoCameraOutlined";
            case "audio" -> "AudioOutlined";
            case "image" -> "PictureOutlined";
            case "document" -> "FileTextOutlined";
            case "font" -> "FontSizeOutlined";
            default -> "FileOutlined";
        };
    }
}
