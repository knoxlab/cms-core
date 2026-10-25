package com.powercms.cms.model;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.common.error.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContentModelValidatorTest {
  private final ContentModelValidator validator = new ContentModelValidator();
  private final ObjectMapper om = new ObjectMapper();

  @Test
  void acceptsCompleteDraft() {
    validator.validate(
        request(
            "ENUM",
            om.createObjectNode()
                .set(
                    "options",
                    om.createArrayNode()
                        .add(om.createObjectNode().put("label", "A").put("value", "a")))));
  }

  @Test
  void acceptsModelWithOnlyTabGroups() {
    // dotCMS 语义：TAB（页签组）即分区，仅含页签组的布局同样满足 SECTION_REQUIRED。
    var tab = new CreateContentTypeRequest.LayoutElementDto(
        null,
        "TAB",
        10,
        null,
        om.createObjectNode().put("title", "Tabs 1").put("variableName", "tabs_1"),
        List.of(
            new CreateContentTypeRequest.LayoutElementDto(
                null,
                "FORM_SECTION",
                1,
                null,
                om.createObjectNode().put("title", "Tab 1").put("tabId", "tab1").put("variableName", "tab_1_1"),
                List.of(node(field("STRING", null)))),
            new CreateContentTypeRequest.LayoutElementDto(
                null,
                "FORM_SECTION",
                101,
                null,
                om.createObjectNode().put("title", "Tab 2").put("tabId", "tab2").put("variableName", "tab_1_2"),
                List.of(node(new CreateContentTypeRequest.FieldDefDto(
                    null, "body_t", "Body", "LONG_TEXT", true, 20, "tab2", null, null, false, null,
                    null, null, false, true, true, false, false, false, false, false))))));
    validator.validate(
        new CreateContentTypeRequest(
            "tabmodel", "TabModel", null, "PAGE", "/tabmodel.ftl", null, null, List.of(tab), null));
  }

  @Test
  void rejectsTabWithoutVariableName() {
    var tab = new CreateContentTypeRequest.LayoutElementDto(
        null,
        "TAB",
        10,
        null,
        om.createObjectNode().put("title", "Tabs 1"),
        List.of(
            new CreateContentTypeRequest.LayoutElementDto(
                null,
                "FORM_SECTION",
                1,
                null,
                om.createObjectNode().put("title", "Tab 1").put("tabId", "tab1").put("variableName", "tab_1_1"),
                List.of(node(field("STRING", null))))));
    assertThatThrownBy(
            () ->
                validator.validate(
                    new CreateContentTypeRequest(
                        "tabmodel", "TabModel", null, "PAGE", "/tabmodel.ftl", null, null, List.of(tab), null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("FIELD_CODE_INVALID");
  }

  @Test
  void rejectsDuplicateCodes() {
    var f = field("STRING", null);
    var section =
        new CreateContentTypeRequest.LayoutElementDto(
            null,
            "FORM_SECTION",
            10,
            null,
            om.createObjectNode().put("title", "Basic").put("variableName", "basic"),
            List.of(node(f), node(f)));
    assertThatThrownBy(
            () ->
                validator.validate(
                    new CreateContentTypeRequest(
                        "article",
                        "Article",
                        null,
                        "PAGE",
                        "/article.ftl",
                        null,
                        null,
                        List.of(section),
                        null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("FIELD_CODE_DUPLICATE");
  }

  @Test
  void rejectsEmptyEnumOptions() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    request("ENUM", om.createObjectNode().set("options", om.createArrayNode()))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("ENUM_OPTIONS_EMPTY");
  }

  @Test
  void acceptsLegacyUnsuffixedCode() {
    validator.validate(request("STRING", null));
  }

  @Test
  void rejectsCamelCaseVariableWithWrongSuffix() {
    var f = new CreateContentTypeRequest.FieldDefDto(null, "productSummary_i", "Summary", "STRING",
        false, 10, "basic", null, null, false, null, null, null, false, false, false,
        false, false, false, false, false);
    assertThatThrownBy(() -> validator.validate(requestWithField(f)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("VARIABLE_NAME_INVALID_SUFFIX");
  }

  @Test
  void rejectsInvalidTypeConfigRegex() {
    var base = request("STRING", null);
    var request = new CreateContentTypeRequest(base.code(), base.name(), base.description(),
        base.templateType(), base.displayTemplate(), base.icon(), base.thumbnail(), base.layout(),
        base.fields(), "PAGE", null, null, "NONE", null, false, null, null,
        om.createObjectNode().set("pathIncludes", om.createArrayNode().add("[")), true, null, null, null, null);
    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("CONFIG_REGEX_INVALID");
  }

  @Test
  void acceptsSupplementalFieldTypes() {
    for (String type : List.of("TAG", "CATEGORY", "HIDDEN", "SITE_FOLDER", "CUSTOM_CODE", "BLOCK_EDITOR")) {
      validator.validate(request(type, null));
    }
    validator.validate(
        request(
            "RELATIONSHIP",
            om.createObjectNode().put("relationId", UUID.randomUUID().toString())));
  }

  @Test
  void rejectsRelationshipWithoutRelationId() {
    assertThatThrownBy(() -> validator.validate(request("RELATIONSHIP", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("RELATION_TYPE_INVALID");
  }

  @Test
  void rejectsConstantWithoutValue() {
    assertThatThrownBy(() -> validator.validate(request("CONSTANT", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void rejectsWidgetSubtypeOnPageType() {
    var base = request("STRING", null);
    var request = new CreateContentTypeRequest(base.code(), base.name(), base.description(),
        base.templateType(), base.displayTemplate(), base.icon(), base.thumbnail(), base.layout(),
        base.fields(), "PAGE", null, null, "NONE", null, false, null, null, null, true, "WIDGET",
        null, null, null);
    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void rejectsFormSubtypeWithInvalidEmail() {
    var base = request("STRING", null);
    var request = new CreateContentTypeRequest(base.code(), base.name(), base.description(),
        base.templateType(), base.displayTemplate(), base.icon(), base.thumbnail(), base.layout(),
        base.fields(), "SHARED", null, null, "NONE", null, false, null, null, null, true, "FORM",
        "not-an-email", null, null);
    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void mediaTypeRequiresValidMediaKind() {
    var request = mediaRequest(null);
    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("MEDIA_KIND_REQUIRED");

    assertThatThrownBy(() -> validator.validate(mediaRequest("archive")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("MEDIA_KIND_REQUIRED");
  }

  @Test
  void acceptsMediaTypeWithValidKind() {
    validator.validate(mediaRequest("video"));
    // 2026-09-12 新增第六类：font 必须为合法 media_kind
    validator.validate(mediaRequest("font"));
  }

  @Test
  void fontKindSpecificSystemFieldsAreGenerated() {
    var font = ContentModelValidator.systemFields("MEDIA", "font");
    assertThat(font).extracting(ContentModelValidator.SystemField::code).contains("format_s");
  }

  /** 2026-09-22 系统字段瘦身 + 页面属性绑定控件：PAGE 含 title_s + 4 绑定；其余非 MEDIA 仅 title_s；退役码不得再生成。 */
  @Test
  void nonMediaTypesExposeOnlyTitleSystemField() {
    for (String objectType : java.util.List.of("COMPONENT", "SHARED", "SECTION")) {
      var fields = ContentModelValidator.systemFields(objectType);
      assertThat(fields).extracting(ContentModelValidator.SystemField::code)
          .containsExactly("title_s")
          .doesNotContainAnyElementsOf(ContentModelValidator.LEGACY_SYSTEM_CODES);
    }
    // PAGE：标题 + 所属栏目/URL/页面模板/可视化模板 四个绑定控件（值映射条目级实体列）；null 按 PAGE 处理
    assertThat(ContentModelValidator.systemFields("PAGE"))
        .extracting(ContentModelValidator.SystemField::code)
        .containsExactly("title_s", "folder_id_s", "route_path_s", "template_path_s", "visual_template_s")
        .doesNotContainAnyElementsOf(ContentModelValidator.LEGACY_SYSTEM_CODES);
    assertThat(ContentModelValidator.systemFields(null))
        .extracting(ContentModelValidator.SystemField::code)
        .containsExactly("title_s", "folder_id_s", "route_path_s", "template_path_s", "visual_template_s");
  }

  /** 退役码清单用于版本快照兼容豁免（ContentService），必须与 V100 迁移清理范围一致。 */
  @Test
  void legacySystemCodesMatchRetiredSet() {
    assertThat(ContentModelValidator.LEGACY_SYSTEM_CODES)
        .containsExactlyInAnyOrder("url_s", "template_s", "column_id_s", "storage_path_s");
  }

  @Test
  void mediaTypeRejectsWidgetOrFormSubtype() {
    var widget = new CreateContentTypeRequest(mediaRequest("video").code(), "视频", null, "MEDIA",
        null, null, null, null, null, "MEDIA", null, null, "NONE", null, false, null, null, null,
        true, "WIDGET", null, "video", null);
    assertThatThrownBy(() -> validator.validate(widget))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void mediaKindSpecificSystemFieldsAreGenerated() {
    var video = ContentModelValidator.systemFields("MEDIA", "video");
    assertThat(video).extracting(ContentModelValidator.SystemField::code)
        .contains("durationMs_i", "width_i", "height_i", "posterUrl_s", "thumbnailUrl_s",
            "hlsMasterUrl_s", "playPlan_t");
    var image = ContentModelValidator.systemFields("MEDIA", "image");
    assertThat(image).extracting(ContentModelValidator.SystemField::code)
        .contains("width_i", "height_i", "thumbnailUrl_s", "dpi_d", "colorSpace_s");
    var audio = ContentModelValidator.systemFields("MEDIA", "audio");
    assertThat(audio).extracting(ContentModelValidator.SystemField::code)
        .contains("coverUrl_s", "thumbnailUrl_s");
    var document = ContentModelValidator.systemFields("MEDIA", "document");
    assertThat(document).extracting(ContentModelValidator.SystemField::code)
        .contains("thumbnailUrl_s", "pageCount_i");
  }

  @Test
  void mediaRefRejectsInvalidAllowedKinds() {
    var cfg = om.createObjectNode()
        .set("allowedKinds", om.createArrayNode().add("video").add("pdf"));
    assertThatThrownBy(() -> validator.validate(request("MEDIA_REF", cfg)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void mediaRefRejectsNonBooleanRequireReady() {
    var cfg = om.createObjectNode().put("requireReady", "yes");
    assertThatThrownBy(() -> validator.validate(request("MEDIA_REF", cfg)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void mediaRefAcceptsValidConfig() {
    var cfg = om.createObjectNode()
        .set("allowedKinds", om.createArrayNode().add("video").add("image"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) cfg).put("requireReady", true);
    validator.validate(request("MEDIA_REF", cfg));
  }

  private CreateContentTypeRequest mediaRequest(String mediaKind) {
    return new CreateContentTypeRequest("media_video", "视频", null, "MEDIA", null, null, null,
        null, null, "MEDIA", null, null, "NONE", null, false, null, null, null, false, null, null,
        mediaKind, null);
  }

  private CreateContentTypeRequest requestWithField(CreateContentTypeRequest.FieldDefDto f) {
    var section = new CreateContentTypeRequest.LayoutElementDto(null, "FORM_SECTION", 10, null,
        om.createObjectNode().put("title", "Basic").put("variableName", "basic"), List.of(node(f)));
    return new CreateContentTypeRequest("article", "Article", null, "PAGE", "/article.ftl",
        null, null, List.of(section), null);
  }
  private CreateContentTypeRequest request(
      String type, com.fasterxml.jackson.databind.JsonNode cfg) {
    var section =
        new CreateContentTypeRequest.LayoutElementDto(
            null,
            "FORM_SECTION",
            10,
            null,
            om.createObjectNode().put("title", "Basic").put("variableName", "basic"),
            List.of(node(field(type, cfg))));
    return new CreateContentTypeRequest(
        "article", "Article", null, "PAGE", "/article.ftl", null, null, List.of(section), null);
  }

  private CreateContentTypeRequest.LayoutElementDto node(CreateContentTypeRequest.FieldDefDto f) {
    return new CreateContentTypeRequest.LayoutElementDto(null, "FIELD", 10, f, null, List.of());
  }

  private CreateContentTypeRequest.FieldDefDto field(
      String type, com.fasterxml.jackson.databind.JsonNode cfg) {
    return new CreateContentTypeRequest.FieldDefDto(
        null, "title", "Title", type, true, 10, "basic", null, null, false, null, cfg, null, false,
        true, true, false, false, false, false, false);
  }

  // ── FR-AI-KB-052：敏感语义的唯一持久化表达是 publicIndex ──────────────────

  /**
   * 以 {@code sensitive=true} 建模必须**显式拒绝**，而不是静默丢弃。
   *
   * <p>{@code field_definition} 表**没有 {@code sensitive} 列**（已核实），
   * 因此该属性无法被持久化。此前的校验只拒绝「{@code publicIndex && sensitive}」，
   * 于是 {@code sensitive=true, publicIndex=false} 会被**接受**，
   * 然后在落库时把 {@code sensitive} **悄悄丢掉**。
   *
   * <p>后果：建模者以为"我把这个字段标成了敏感"，而系统里只留下一个普通的
   * {@code publicIndex=false} 字段 —— 意图没有被记录，也没有任何提示。
   * 需求的口径是「{@code publicIndex=false} 就是敏感语义的唯一持久化表达」，
   * 因此真正要做的是**让这个口径成为唯一入口**，而不是接受一个会被丢掉的属性。
   */
  @Test
  void rejectsSensitiveFlagBecauseThereIsNoColumnForIt() {
    // sensitive=true, publicIndex=false → 此前被接受并静默丢弃
    var f = fieldWithFlags("STRING", null, /* adminIndex */ false, /* publicIndex */ false,
        /* sensitive */ true);

    assertThatThrownBy(() -> validator.validate(requestWithField(f)))
        .as("sensitive 无列可存：接受它等于接受一个会被静默丢掉的标记")
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("publicIndex")
        .hasMessageContaining("敏感");
  }

  @Test
  void rejectsSensitiveFlagEvenWithPublicIndex() {
    var f = fieldWithFlags("STRING", null, false, /* publicIndex */ true, /* sensitive */ true);

    assertThatThrownBy(() -> validator.validate(requestWithField(f)))
        .isInstanceOf(BusinessException.class);
  }

  /** 正常路径不受影响：只用 publicIndex 表达。 */
  @Test
  void acceptsPublicIndexOnlyMarking() {
    var f = fieldWithFlags("STRING", null, false, /* publicIndex */ false, /* sensitive */ false);

    validator.validate(requestWithField(f)); // 不抛即通过
  }

  @Test
  void acceptsPublicIndexedNonSensitiveField() {
    var f = fieldWithFlags("STRING", null, false, /* publicIndex */ true, /* sensitive */ false);

    validator.validate(requestWithField(f));
  }

  /** 构造一个可独立设置三个开关的字段定义（顺序见 CreateContentTypeRequest.FieldDefDto）。 */
  private CreateContentTypeRequest.FieldDefDto fieldWithFlags(
      String type, com.fasterxml.jackson.databind.JsonNode cfg,
      boolean adminIndex, boolean publicIndex, boolean sensitive) {
    return new CreateContentTypeRequest.FieldDefDto(
        null, "title", "Title", type, true, 10, "basic", null, null, false, null, cfg, null, false,
        adminIndex, publicIndex, sensitive, false, false, false, false);
  }
}
