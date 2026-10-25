package com.powercms.cms.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.common.error.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ContentModelValidator {
  public static final Set<String> TYPES =
      Set.of(
          "STRING",
          "LONG_TEXT",
          "RICH_TEXT",
          "IMAGE",
          "INTEGER",
          "DECIMAL",
          "BOOLEAN",
          "DATE_TIME",
          "ENUM",
          "MULTI_ENUM",
          "CONTENT_REF",
          "MEDIA_REF",
          "TAXONOMY_REF",
          "COMPONENT_REF",
          "KEY_VALUE",
          "JSON",
          "COLOR",
          "TAG",
          "CATEGORY",
          "HIDDEN",
          "SITE_FOLDER",
          "RELATIONSHIP",
          "CONSTANT",
          "CUSTOM_CODE",
          "BLOCK_EDITOR",
          "FORM_SECTION",
          "REPEAT_GROUP",
          "TAB",
          "ROW",
          "COLUMN",
          "LINE_DIVIDER");

  /** 结构控件：不直接存储值，而是组织字段的布局和结构（FR-CM-FIELDTYPE-002，19.6）。 */
  public static final Set<String> STRUCTURAL =
      Set.of("FORM_SECTION", "REPEAT_GROUP", "TAB", "ROW", "COLUMN", "LINE_DIVIDER");

  private static final Pattern CODE = Pattern.compile("[a-z][a-zA-Z0-9_]{0,99}");
  public static final Set<String> RESERVED = Set.of(
      "file-name", "internal-name", "placeInNav", "disabled", "objectId", "createdDate",
      "content-type", "display-template", "lastModifiedDate", "locale", "label", "keywords");

  /** 媒体 kind 合法值（FR-CM-MEDIA-006）。 */
  public static final Set<String> MEDIA_KINDS =
      Set.of("video", "audio", "image", "document", "font", "other");

  /** 系统字段定义（FR-CM-SYSFIELD-001）：按 object_type 自动生成、不可删除、类型不可改。 */
  public record SystemField(String code, String name, String fieldType, String systemFieldType) {}

  public static List<SystemField> systemFields(String objectType) {
    return systemFields(objectType, null);
  }

  public static List<SystemField> systemFields(String objectType, String mediaKind) {
    if ("MEDIA".equals(objectType)) {
      List<SystemField> fields = new ArrayList<>();
      // 通用媒体系统字段（FR-CM-MEDIA-003）
      fields.add(new SystemField("title_s", "标题", "STRING", "title"));
      fields.add(new SystemField("fileName_s", "原始文件名", "STRING", "fileName"));
      fields.add(new SystemField("mimeType_s", "MIME 类型", "STRING", "mimeType"));
      fields.add(new SystemField("fileSize_i", "文件大小", "INTEGER", "fileSize"));
      fields.add(new SystemField("storageKey_s", "存储键", "STRING", "storageKey"));
      fields.add(new SystemField("hash_s", "内容哈希", "STRING", "hash"));
      fields.add(new SystemField("kind_s", "媒体种类", "STRING", "kind"));
      fields.add(new SystemField("status_s", "媒体状态", "STRING", "status"));
      fields.addAll(kindSpecificSystemFields(mediaKind));
      return fields;
    }
    // 2026-09-22 页面属性绑定控件（需求：四项各自做成可拖放绑定控件）：
    // 所属栏目/URL/页面模板/可视化模板 与 title_s 同机制的 🔒 系统字段（新建模型即可见、
    // 可拖放到任意行/列），值映射到条目级实体列（folder_id/route_path/template_path/
    // 页面组成绑定），**不写 fieldValues**（title_s 为契约码例外，两处均写）。
    // 仅 PAGE 类型需要：页面管理的路由/树落位/渲染/可视化组装能力，任何 PAGE 模型都需要。
    if (objectType == null || "PAGE".equals(objectType)) {
      return List.of(
          new SystemField("title_s", "标题", "STRING", "title"),
          new SystemField("folder_id_s", "所属栏目", "STRING", "folder"),
          new SystemField("route_path_s", "URL", "STRING", "route"),
          new SystemField("template_path_s", "页面模板", "STRING", "template"),
          new SystemField("visual_template_s", "可视化模板", "STRING", "visualTemplate"));
    }
    // 2026-09-22 系统字段瘦身：其余非 MEDIA 类型仅保留 title_s。
    // url_s/template_s（与条目级 route_path/template_path 重复、全仓无消费方）、
    // column_id_s（与 folder_id 重复）、storage_path_s（无消费方）已退役，见 legacySystemCodes()。
    // title_s 是唯一保留的通用系统字段：媒体管道回写（MediaContentItemSyncService）、
    // AI 建模建议（SuggestContentTypeTool）按此码消费。
    return List.of(new SystemField("title_s", "标题", "STRING", "title"));
  }

  /**
   * 已退役的历史系统字段码（2026-09-22 系统字段瘦身）。
   *
   * <p>存量字段与布局行由 Flyway V100 清理；历史版本快照的 fieldValues 中仍可能残留这些键，
   * {@code ContentService} 的版本兼容性校验按本清单豁免（对应值早已由条目级
   * 标题/路径/模板实体列在版本行中独立记录），保证老版本可查看、可恢复。
   */
  public static final Set<String> LEGACY_SYSTEM_CODES =
      Set.of("url_s", "template_s", "column_id_s", "storage_path_s");

  /** 按 media_kind 的专属系统字段（FR-CM-MEDIA-004）。thumbnailUrl_s 为统一预览图字段（Knox poster 截帧/源图）。 */
  private static List<SystemField> kindSpecificSystemFields(String mediaKind) {
    return switch (mediaKind == null ? "" : mediaKind) {
      case "video" -> List.of(
          new SystemField("durationMs_i", "时长(ms)", "INTEGER", "durationMs"),
          new SystemField("width_i", "宽度", "INTEGER", "width"),
          new SystemField("height_i", "高度", "INTEGER", "height"),
          new SystemField("bitrate_d", "码率", "DECIMAL", "bitrate"),
          new SystemField("format_s", "格式", "STRING", "format"),
          new SystemField("posterUrl_s", "海报 URL", "STRING", "posterUrl"),
          new SystemField("thumbnailUrl_s", "预览图", "STRING", "thumbnailUrl"),
          new SystemField("hlsMasterUrl_s", "HLS 主地址", "STRING", "hlsMasterUrl"),
          new SystemField("playPlan_t", "播放计划", "LONG_TEXT", "playPlan"),
          new SystemField("drm_b", "DRM 加密", "BOOLEAN", "drm"),
          new SystemField("transcript_t", "转写稿", "LONG_TEXT", "transcript"),
          new SystemField("transcriptLang_s", "转写语种", "STRING", "transcriptLang"),
          new SystemField("transcriptModel_s", "转写模型", "STRING", "transcriptModel"));
      case "audio" -> List.of(
          new SystemField("durationMs_i", "时长(ms)", "INTEGER", "durationMs"),
          new SystemField("bitrate_d", "码率", "DECIMAL", "bitrate"),
          new SystemField("format_s", "格式", "STRING", "format"),
          new SystemField("coverUrl_s", "封面 URL", "STRING", "coverUrl"),
          new SystemField("thumbnailUrl_s", "预览图", "STRING", "thumbnailUrl"),
          new SystemField("transcript_t", "转写稿", "LONG_TEXT", "transcript"),
          new SystemField("transcriptLang_s", "转写语种", "STRING", "transcriptLang"),
          new SystemField("transcriptModel_s", "转写模型", "STRING", "transcriptModel"));
      case "image" -> List.of(
          new SystemField("width_i", "宽度", "INTEGER", "width"),
          new SystemField("height_i", "高度", "INTEGER", "height"),
          new SystemField("format_s", "格式", "STRING", "format"),
          new SystemField("thumbnailUrl_s", "预览图", "STRING", "thumbnailUrl"),
          new SystemField("dpi_d", "DPI", "DECIMAL", "dpi"),
          new SystemField("colorSpace_s", "色彩空间", "STRING", "colorSpace"));
      case "document" -> List.of(
          new SystemField("pageCount_i", "页数", "INTEGER", "pageCount"),
          new SystemField("author_s", "作者", "STRING", "author"),
          new SystemField("format_s", "格式", "STRING", "format"),
          new SystemField("thumbnailUrl_s", "预览图", "STRING", "thumbnailUrl"));
      // 字体：Knox 不解析字体元数据，仅格式可填（与 media_other 同口径）。
      case "font" -> List.of(
          new SystemField("format_s", "格式", "STRING", "format"));
      default -> List.of(new SystemField("format_s", "格式", "STRING", "format"));
    };
  }

  public void validate(CreateContentTypeRequest req) {
    String tpl = type(req.templateType());
    if (!Set.of("PAGE", "COMPONENT", "SECTION", "MEDIA").contains(tpl))
      fail(ErrorCodes.VALIDATION_FAILED, "templateType", "模板类型必须是 PAGE、COMPONENT、SECTION 或 MEDIA");
    String objectType = req.objectType() == null ? objectType(req.templateType()) : req.objectType();
    if (!Set.of("PAGE", "COMPONENT", "SHARED", "MEDIA", "SURVEY", "EXAM", "ROOM").contains(objectType))
      fail(ErrorCodes.VALIDATION_FAILED, "objectType", "objectType 必须是 PAGE、COMPONENT、SHARED、MEDIA、SURVEY、EXAM 或 ROOM");
    if ("MEDIA".equals(objectType)
        && (req.mediaKind() == null || !MEDIA_KINDS.contains(req.mediaKind())))
      fail(ErrorCodes.MEDIA_KIND_REQUIRED, "mediaKind", "MEDIA 类型必须配置合法的 mediaKind（video/audio/image/document/font/other）");
    if (("PAGE".equals(objectType) || "SHARED".equals(objectType))
        && (req.displayTemplate() == null || req.displayTemplate().isBlank()))
      fail(ErrorCodes.VALIDATION_FAILED, "displayTemplate", "PAGE/SHARED 类型必须配置显示模板");
    if (req.displayTemplate() != null && !req.displayTemplate().isBlank()
        && !req.displayTemplate().trim().toLowerCase(Locale.ROOT).endsWith(".ftl"))
      fail(ErrorCodes.BAD_REQUEST, "displayTemplate", "显示模板必须使用 .ftl 扩展名");
    if (!Set.of("NONE", "MERGE_FIELDS", "MERGE_DATA").contains(req.mergeStrategy() == null ? "NONE" : req.mergeStrategy()))
      fail(ErrorCodes.VALIDATION_FAILED, "mergeStrategy", "继承模式非法");
    if (req.quickCreate() && (req.destinationPattern() == null || req.destinationPattern().isBlank()))
      throw new BusinessException(ErrorCodes.QUICK_CREATE_DESTINATION_REQUIRED, "快速创建必须配置目标路径模式");
    validateSubtype(req);
    validateConfig(req.config());
    List<CreateContentTypeRequest.LayoutElementDto> layout = normalized(req);
    // TAB（页签组）在内容编辑对话框同样呈现为分区页签，视为分区（dotCMS 语义）。
    if (layout.stream().noneMatch(x -> "FORM_SECTION".equals(x.elementType()) || "TAB".equals(x.elementType())))
      throw new BusinessException(ErrorCodes.SECTION_REQUIRED, "内容模型至少包含一个表单分区");
    Set<String> codes = new HashSet<>();
    walk(layout, codes, 0);
  }

  public List<CreateContentTypeRequest.LayoutElementDto> normalized(CreateContentTypeRequest req) {
    if (req.layout() != null && !req.layout().isEmpty()) return req.layout();
    var children =
        req.fields() == null
            ? List.<CreateContentTypeRequest.LayoutElementDto>of()
            : req.fields().stream()
                .map(
                    f ->
                        new CreateContentTypeRequest.LayoutElementDto(
                            f.id(), "FIELD", f.sortOrder(), f, null, List.of()))
                .toList();
    JsonNode config =
        new com.fasterxml.jackson.databind.node.ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance)
            .put("title", "基本属性")
            .put("variableName", "basic")
            .put("collapsed", false);
    return List.of(
        new CreateContentTypeRequest.LayoutElementDto(
            null, "FORM_SECTION", 10, null, config, children));
  }

  private void walk(
      List<CreateContentTypeRequest.LayoutElementDto> nodes, Set<String> codes, int repeatDepth) {
    for (var n : nodes) {
      String et = n.elementType();
      if (!Set.of("FIELD", "FORM_SECTION", "REPEAT_GROUP", "TAB", "ROW", "COLUMN", "LINE_DIVIDER").contains(et))
        fail(ErrorCodes.FIELD_TYPE_UNSUPPORTED, "layout.elementType", "不支持的布局元素 " + et);
      var f = n.field();
      String code = f != null ? f.code() : text(n.config(), "variableName");
      String ft = f != null ? f.fieldType() : et;
      if (code == null || !CODE.matcher(code).matches())
        fail(ErrorCodes.FIELD_CODE_INVALID, "code", "字段代码必须匹配 [a-z][a-z0-9_]{0,99}: " + code);
      if (RESERVED.contains(code))
        fail(ErrorCodes.VARIABLE_NAME_RESERVED, "code", "字段变量名为保留字: " + code);
      if (f != null && code.chars().anyMatch(Character::isUpperCase) && !code.endsWith(suffix(ft)))
        fail(ErrorCodes.VARIABLE_NAME_INVALID_SUFFIX, "code", "字段变量名后缀应为 " + suffix(ft));
      if (!codes.add(code))
        throw new BusinessException(
            ErrorCodes.FIELD_CODE_DUPLICATE,
            "字段代码重复: " + code,
            List.of(new ApiError.FieldError("code", code)));
      if (!TYPES.contains(ft))
        fail(ErrorCodes.FIELD_TYPE_UNSUPPORTED, "fieldType", "不支持的字段类型: " + ft);
      JsonNode cfg = f != null && f.fieldConfig() != null ? f.fieldConfig() : n.config();
      if (("ENUM".equals(ft) || "MULTI_ENUM".equals(ft))) {
        JsonNode opts = cfg == null ? null : cfg.get("options");
        if (opts == null || !opts.isArray() || opts.isEmpty())
          throw new BusinessException(ErrorCodes.ENUM_OPTIONS_EMPTY, "枚举选项不能为空");
        Set<String> values = new HashSet<>();
        for (JsonNode o : opts)
          if (!values.add(text(o, "value")))
            fail(ErrorCodes.VALIDATION_FAILED, "options", "枚举 value 必须唯一");
      }
      if ("RELATIONSHIP".equals(ft) && (cfg == null || !cfg.hasNonNull("relationId")))
        fail(ErrorCodes.RELATION_TYPE_INVALID, "fieldConfig.relationId", "关系字段必须配置 relationId");
      if ("CONSTANT".equals(ft) && (cfg == null || !cfg.hasNonNull("constantValue")))
        fail(ErrorCodes.VALIDATION_FAILED, "fieldConfig.constantValue", "常量字段必须配置 constantValue");
      int depth = repeatDepth + ("REPEAT_GROUP".equals(et) ? 1 : 0);
      if (depth > 3)
        throw new BusinessException(ErrorCodes.NESTING_DEPTH_EXCEEDED, "重复组嵌套深度不能超过 3");
      if ("REPEAT_GROUP".equals(et)) {
        int min = integer(cfg, "minItems", 0), max = integer(cfg, "maxItems", -1);
        if (min < 0 || (max != -1 && max < min))
          fail(ErrorCodes.VALIDATION_FAILED, "fieldConfig", "重复组 minItems/maxItems 非法");
      }
      // FR-AI-KB-052：**sensitive 没有数据库列**，因此它无法被持久化 ——
      // 接受它等于接受一个会被静默丢掉的标记（建模者以为「我把这个字段标成了敏感」，
      // 系统里只留下一个普通的 publicIndex=false 字段，且没有任何提示）。
      //
      // 需求口径是「publicIndex=false 就是敏感语义的**唯一持久化表达**」，
      // 因此正确做法是**让这个口径成为唯一入口**：用 publicIndex 同时承载
      // 「不进访客索引」与敏感标记，而不是再接一个在别处表达的 sensitive。
      //
      // 这一条比原来那条（publicIndex && sensitive）**更严**：原先只拒绝两者并存，
      // 单独的 sensitive=true 会被放过并在落库时静默丢弃。
      if (f != null && f.sensitive()) {
        throw new BusinessException(ErrorCodes.FORBIDDEN,
          "敏感字段不能进入访客索引：请用 publicIndex=false 表达敏感语义"
              + "（field_definition 没有 sensitive 列，该属性无法被持久化）");
      }
      if (f != null && "MEDIA_REF".equals(ft)) validateMediaRefConfig(cfg);
      walk(n.children() == null ? List.of() : n.children(), codes, depth);
    }
  }

  /** MEDIA_REF 专属属性校验（FR-CM-MEDIA-021）：allowedKinds 合法值、requireReady 布尔。 */
  private static void validateMediaRefConfig(JsonNode cfg) {
    if (cfg == null) return;
    JsonNode kinds = cfg.get("allowedKinds");
    if (kinds != null && kinds.isArray()) {
      for (JsonNode kind : kinds) {
        if (!MEDIA_KINDS.contains(kind.asText())) {
          fail(ErrorCodes.VALIDATION_FAILED, "fieldConfig.allowedKinds",
              "allowedKinds 必须是 video/audio/image/document/font/other: " + kind.asText());
        }
      }
    }
    JsonNode requireReady = cfg.get("requireReady");
    if (requireReady != null && !requireReady.isBoolean()) {
      fail(ErrorCodes.VALIDATION_FAILED, "fieldConfig.requireReady", "requireReady 必须是布尔值");
    }
  }

  private static String suffix(String type) {
    return switch (type) {
      case "LONG_TEXT" -> "_t"; case "RICH_TEXT" -> "_html"; case "INTEGER" -> "_i";
      case "DECIMAL" -> "_d"; case "BOOLEAN" -> "_b"; case "DATE_TIME" -> "_dt";
      case "MULTI_ENUM", "KEY_VALUE", "TAG", "RELATIONSHIP" -> "_ss"; default -> "_s";
    };
  }

  private static String objectType(String templateType) {
    if ("MEDIA".equals(templateType)) return "MEDIA";
    return "COMPONENT".equals(templateType) ? "COMPONENT" : "PAGE";
  }

  public void validateConfig(JsonNode config) {
    if (config == null || config.isNull()) return;
    for (String key : List.of("pathIncludes", "pathExcludes", "cdataEscapedFieldPatterns")) {
      JsonNode values = config.get(key);
      if (values != null && values.isArray()) for (JsonNode value : values) try {
        Pattern.compile(value.asText());
      } catch (RuntimeException ex) {
        fail(ErrorCodes.CONFIG_REGEX_INVALID, "config." + key, "正则表达式非法: " + value.asText());
      }
    }
  }

  private void validateSubtype(CreateContentTypeRequest req) {
    String subtype = req.subtype() == null || req.subtype().isBlank() ? null : req.subtype().trim();
    if (subtype != null && !Set.of("WIDGET", "FORM").contains(subtype))
      fail(ErrorCodes.VALIDATION_FAILED, "subtype", "subtype 必须是 WIDGET 或 FORM");
    if (subtype == null) return;
    String objectType = req.objectType() == null ? objectType(req.templateType()) : req.objectType();
    if ("MEDIA".equals(objectType))
      fail(ErrorCodes.VALIDATION_FAILED, "subtype", "MEDIA 类型不支持 WIDGET/FORM 子类型");
    if ("WIDGET".equals(subtype) && !"COMPONENT".equals(objectType))
      fail(ErrorCodes.VALIDATION_FAILED, "subtype", "Widget 子类型必须是 COMPONENT 类型");
    if ("FORM".equals(subtype) && !"SHARED".equals(objectType))
      fail(ErrorCodes.VALIDATION_FAILED, "subtype", "Form 子类型必须是 SHARED 类型");
    if ("FORM".equals(subtype) && req.formNotificationEmails() != null
        && !req.formNotificationEmails().isBlank())
      validateEmails(req.formNotificationEmails());
  }

  private void validateEmails(String raw) {
    String trimmed = raw.trim();
    List<String> emails;
    if (trimmed.startsWith("[")) {
      try {
        emails = new ArrayList<>();
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(trimmed)
            .forEach(n -> emails.add(n.asText()));
      } catch (Exception e) {
        fail(ErrorCodes.VALIDATION_FAILED, "formNotificationEmails", "通知邮箱列表格式非法");
        return;
      }
    } else {
      emails = List.of(trimmed.split("[,;]"));
    }
    for (String email : emails) {
      if (email == null || email.isBlank()) continue;
      if (!email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
        fail(ErrorCodes.VALIDATION_FAILED, "formNotificationEmails", "邮箱格式非法: " + email.trim());
    }
  }

  private static String type(String v) {
    return v == null ? "PAGE" : v;
  }

  private static String text(JsonNode n, String k) {
    JsonNode v = n == null ? null : n.get(k);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static int integer(JsonNode n, String k, int d) {
    JsonNode v = n == null ? null : n.get(k);
    return v == null ? d : v.asInt(d);
  }

  private static void fail(String c, String f, String m) {
    throw new BusinessException(c, m, List.of(new ApiError.FieldError(f, m)));
  }
}
