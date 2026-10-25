package com.powercms.cms.platform.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 平台支持的界面语言白名单（FR-I18N-001）：zh-CN / en-US / ja-JP / ko-KR / fr-FR / es-ES。
 * 用户偏好、请求头、组织默认值都必须归一到该白名单后才能生效。
 */
public final class SupportedLocales {

    /** 白名单顺序即语言选择器展示顺序。 */
    public static final List<Locale> SUPPORTED = List.of(
        Locale.forLanguageTag("zh-CN"),
        Locale.forLanguageTag("en-US"),
        Locale.forLanguageTag("ja-JP"),
        Locale.forLanguageTag("ko-KR"),
        Locale.forLanguageTag("fr-FR"),
        Locale.forLanguageTag("es-ES"));

    public static final Set<String> SUPPORTED_TAGS = SUPPORTED.stream()
        .map(SupportedLocales::tag)
        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

    public static final String DEFAULT_TAG = "zh-CN";

    private SupportedLocales() {}

    /** 规范标签（小写语言 + 大写地区，如 zh-CN）。 */
    public static String tag(Locale locale) {
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry() == null ? "" : locale.getCountry().toUpperCase(Locale.ROOT);
        return country.isEmpty() ? language : language + "-" + country;
    }

    /** 输入值是否为受支持语言（大小写不敏感）。 */
    public static boolean isSupported(String tag) {
        return tag != null && SUPPORTED_TAGS.contains(normalize(tag));
    }

    /**
     * 归一用户/管理员输入：大小写不敏感，支持 ISO 639 短码（en → en-US）与
     * 兼容别名（zh → zh-CN、zh-Hans → zh-CN、es-419 → es-ES）。
     */
    public static String normalize(String tag) {
        if (tag == null || tag.isBlank()) return null;
        String candidate = tag.trim().replace('_', '-');
        String lower = candidate.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "zh", "zh-hans", "zh-cn", "zh-sg" -> "zh-CN";
            case "en", "en-us", "en-sg" -> "en-US";
            case "ja", "ja-jp" -> "ja-JP";
            case "ko", "ko-kr" -> "ko-KR";
            case "fr", "fr-fr", "fr-be", "fr-ca" -> "fr-FR";
            case "es", "es-es", "es-419", "es-mx", "es-ar" -> "es-ES";
            default -> null;
        };
    }

    /** 由任意候选标签解析受支持 Locale，未命中返回 null。 */
    public static Locale parse(String tag) {
        String normalized = normalize(tag);
        if (normalized == null) return null;
        for (Locale locale : SUPPORTED) {
            if (tag(locale).equals(normalized)) return locale;
        }
        return null;
    }

    /** 解析失败时回落默认语言。 */
    public static Locale parseOrDefault(String tag) {
        Locale locale = parse(tag);
        return locale != null ? locale : Locale.forLanguageTag(DEFAULT_TAG);
    }
}
