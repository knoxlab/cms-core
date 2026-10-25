package com.powercms.cms.model.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.model.MediaTypeSeedService;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptSandboxServiceTest {
  private final ScriptSandboxService sandbox = new ScriptSandboxService(new ObjectMapper());

  @Test
  void rejectsFilesystemAccess() {
    assertThatThrownBy(() -> sandbox.render("new java.io.File('/etc/passwd')", "JS", null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("CUSTOM_CODE_COMPILE_ERROR");
  }

  @Test
  void rejectsRuntimeExec() {
    assertThatThrownBy(() -> sandbox.render("Runtime.getRuntime().exec('ls')", "JS", null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("CUSTOM_CODE_COMPILE_ERROR");
  }

  @Test
  void rejectsReflection() {
    assertThatThrownBy(() -> sandbox.render("java.lang.reflect.Method", "JS", null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("CUSTOM_CODE_COMPILE_ERROR");
  }

  @Test
  void rejectsUnbalancedBraces() {
    assertThatThrownBy(() -> sandbox.render("function f( { return 1;", "JS", null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("CUSTOM_CODE_COMPILE_ERROR");
  }

  @Test
  void rejectsUnsupportedLanguage() {
    assertThatThrownBy(() -> sandbox.render("return 1", "PYTHON", null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("CUSTOM_CODE_COMPILE_ERROR");
  }

  @Test
  void safeCodePassesStaticValidation() {
    var result = sandbox.render("return 'ok'", "JS", null);
    assertThat(result.valid()).isTrue();
  }

  // ---- 预置媒体 WEBHOOK 钩子脚本（MediaTypeSeedService.defaultWebhookScript）----

  @Test
  void presetWebhookWritesMetadataOnMetadataEvent() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("thumbnailUrl_s", "/posters/42.jpg");
    fields.put("posterUrl_s", "/posters/42.jpg");
    Map<String, Object> media = new LinkedHashMap<>();
    media.put("width", 1920);
    media.put("height", 1080);
    media.put("bitrate", 2500000L);
    media.put("durationMs", 90000);
    media.put("format", "mp4");
    media.put("posterPath", "/posters/42.jpg");

    Object result = sandbox.evaluate(MediaTypeSeedService.defaultWebhookScript(), "GROOVY",
        Map.of("fields", fields, "media", media, "asset", Map.of("id", "1", "kind", "video"),
            "event", "metadata.obtained"));

    assertThat(result).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<Object, Object> patch = (Map<Object, Object>) result;
    // 元数据事件：webhook 回调元数据写入对应字段值
    assertThat(patch).containsEntry("width_i", 1920).containsEntry("height_i", 1080)
        .containsEntry("bitrate_d", 2500000L).containsEntry("durationMs_i", 90000)
        .containsEntry("format_s", "mp4");
    // 非封面事件：不做 poster 规范化
    assertThat(patch).doesNotContainKey("thumbnailUrl_s");
  }

  @Test
  void presetWebhookNormalizesPosterOnCoverEvent() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("thumbnailUrl_s", "/posters/42.jpg");
    fields.put("posterUrl_s", "/posters/42.jpg");

    Object result = sandbox.evaluate(MediaTypeSeedService.defaultWebhookScript(), "GROOVY",
        Map.of("fields", fields, "media", Map.of(), "asset", Map.of("id", "1"), "event", "poster.obtained"));

    @SuppressWarnings("unchecked")
    Map<Object, Object> patch = (Map<Object, Object>) result;
    // 封面事件：poster 相对路径拼接基址 + 处理完成打标
    assertThat(patch).containsEntry("thumbnailUrl_s", "http://knox-media:8200/posters/42.jpg")
        .containsEntry("posterUrl_s", "http://knox-media:8200/posters/42.jpg")
        .containsEntry("coverReady_b", true);
  }

  @Test
  void presetWebhookIgnoresZeroAndConvertsDurationSeconds() {
    Map<String, Object> media = new LinkedHashMap<>();
    media.put("width", 0);     // Knox 用 0 表示"无"：不落字段
    media.put("height", 720);
    media.put("bitrate", 0L);
    media.put("duration", 90); // 秒 → 毫秒
    media.put("format", "mp4");

    Object result = sandbox.evaluate(MediaTypeSeedService.defaultWebhookScript(), "GROOVY",
        Map.of("fields", Map.of(), "media", media, "asset", Map.of("id", "1"), "event", "metadata.obtained"));

    @SuppressWarnings("unchecked")
    Map<Object, Object> patch = (Map<Object, Object>) result;
    assertThat(patch).doesNotContainKey("width_i").doesNotContainKey("bitrate_d")
        .containsEntry("height_i", 720).containsEntry("durationMs_i", 90000);
  }

  @Test
  void presetWebhookNoopsOnUnrelatedEvent() {
    Object result = sandbox.evaluate(MediaTypeSeedService.defaultWebhookScript(), "GROOVY",
        Map.of("fields", Map.of("thumbnailUrl_s", "/posters/42.jpg"), "media", Map.of("width", 1920),
            "asset", Map.of("id", "1"), "event", "keyframe.obtained"));

    assertThat(result).isNull();
  }
}
