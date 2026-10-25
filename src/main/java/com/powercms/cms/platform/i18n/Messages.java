package com.powercms.cms.platform.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 消息解析门面（FR-I18N-002）：统一从当前请求语言（LocaleContextHolder）的资源包取文案。
 * <ul>
 *   <li>{@link #get(String, Object...)} 按稳定键翻译（框架层消息，占位符 {@code {0}}）；
 *       运行期拼装的句子（如「站点尚未配置 X 环境的 Knox 端点」）应走这条路径，
 *       目标语言的语序由文案自身决定；</li>
 *   <li>{@link #byText(String)} 按"中文原文即键"翻译遗留消息：先整句查键，未命中时按
 *       ASCII/全角标点切分为片段逐段翻译后重组（与 scripts/build-i18n-pieces.mjs 的
 *       切分规则严格一致，键由 scripts/generate-legacy-bundles.mjs 生成）。重组的
 *       **空白一律取自源片段**，目录只负责译文（OBS-MS-04）。</li>
 * </ul>
 */
public final class Messages {

    private static final String TEXT_KEY_PREFIX = "legacy.";
    /** 全角标点单字切分点（嵌套异常消息以「（」粘入外层消息时仍可逐段命中）。 */
    private static final String CJK_PUNCT = "（）「」『』【】，。：；！？、";

    /** 全角标点 → 各语言标点映射；翻译目标语言的键为 {@code punct.<标点>}。 */
    private static Map<String, String> punctKeys() {
        Map<String, String> keys = new java.util.HashMap<>();
        keys.put("，", "punct.comma");
        keys.put("。", "punct.period");
        keys.put("：", "punct.colon");
        keys.put("；", "punct.semicolon");
        keys.put("！", "punct.exclamation");
        keys.put("？", "punct.question");
        keys.put("、", "punct.enumeration-comma");
        keys.put("（", "punct.paren-open");
        keys.put("）", "punct.paren-close");
        keys.put("「", "punct.bracket-open");
        keys.put("」", "punct.bracket-close");
        keys.put("『", "punct.inner-bracket-open");
        keys.put("』", "punct.inner-bracket-close");
        keys.put("【", "punct.lenticular-open");
        keys.put("】", "punct.lenticular-close");
        return Map.copyOf(keys);
    }

    private static final Map<String, String> PUNCT_KEYS = punctKeys();

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 按稳定键 + 参数解析消息；缺键时返回键本身。 */
    public String get(String code, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(code, args, code, locale);
    }

    /** 按稳定键 + 显式语言解析消息；供 Security 链内尚未经过 UserLocaleFilter 的响应使用。 */
    public String get(String code, Locale locale, Object... args) {
        return messageSource.getMessage(code, args, code, locale);
    }

    /** 按中文原文翻译（遗留消息目录键），未收录时原样返回。 */
    public String byText(String text) {
        return byText(text, LocaleContextHolder.getLocale());
    }

    /** 按中文原文以指定语言翻译；整句未命中时按片段逐段翻译后重组。 */
    public String byText(String text, Locale locale) {
        if (text == null || text.isEmpty()) return text;
        String whole = messageSource.getMessage(keyOf(text), null, null, locale);
        if (whole != null) return whole;
        List<String> parts = split(text);
        if (parts.size() == 1) return text;
        StringBuilder out = new StringBuilder();
        boolean atSentenceStart = true;
        for (String part : parts) {
            String translated = translatePart(part, locale);
            if (!translated.isEmpty()) {
                if (atSentenceStart && !out.isEmpty()) {
                    translated = Character.toUpperCase(translated.charAt(0))
                        + translated.substring(1);
                }
                out.append(translated);
                if (!translated.endsWith(" ") && !translated.endsWith(", ")) atSentenceStart = false;
            }
        }
        return out.toString().stripTrailing();
    }

    /**
     * 单片段翻译：中文片段查 legacy 键，全角标点查 punct 键，其余原样。
     *
     * <p>OBS-MS-04：片段的**首尾空白属于模板**（如 {@code " 环境的 "} 的首空格来自
     * {@code "…ACTIVE 状态的…"} 这类源文案），但 {@code .properties} 解析会吞掉值的首部空白
     * （前导空白在 {@code =} 后被跳过），目录里的译文因此普遍缺首空格，逐段拼接就粘成
     * {@code "PREVIEWenvironment"}、{@code "ACTIVEstatus"}。</p>
     *
     * <p>这里把空白改为「一律以源片段为准」：查键前剥离首尾空白，命中后把源片段的空白补回，
     * 目录只负责译文本身。这样丢空格在机制上不再可能发生（目录里空格写错也不影响输出），
     * 中文基线也因此能对源文案原样往返。</p>
     */
    private String translatePart(String part, Locale locale) {
        String punctKey = PUNCT_KEYS.get(part);
        if (punctKey != null) return messageSource.getMessage(punctKey, null, part, locale);
        if (!containsCjk(part)) return part;

        int start = 0;
        int end = part.length();
        while (start < end && Character.isWhitespace(part.charAt(start))) start++;
        while (end > start && Character.isWhitespace(part.charAt(end - 1))) end--;
        if (start == end) return part;

        String core = part.substring(start, end);
        // 归一形（去首尾空白）优先；未命中再试历史条目里「带空白片段」的原样键
        String translated = messageSource.getMessage(keyOf(core), null, null, locale);
        if (translated == null && !core.equals(part)) {
            translated = messageSource.getMessage(keyOf(part), null, null, locale);
        }
        if (translated == null) return part;
        return part.substring(0, start) + translated.strip() + part.substring(end);
    }

    /** 资源包中的键形如 legacy.中文片段，由脚本保证 zh-CN 基线与 Java 源一致。 */
    public static String keyOf(String text) {
        return TEXT_KEY_PREFIX + text;
    }

    /**
     * 与 scripts/build-i18n-pieces.mjs 的 SPLIT 规则严格一致：
     * ASCII 可见段 [!-~]+ 与全角标点 （）「」『』【】，。：；！？、 为切分点，
     * 其余（含中文与空白）合并为片段。
     */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        text.codePoints().forEach(cp -> {
            char c = Character.toChars(cp)[0];
            if (cp >= '!' && cp <= '~' || CJK_PUNCT.indexOf(c) >= 0) {
                if (piece.length() > 0) parts.add(piece.toString());
                piece.setLength(0);
                parts.add(String.valueOf(c));
            } else {
                piece.appendCodePoint(cp);
            }
        });
        if (piece.length() > 0) parts.add(piece.toString());
        return parts;
    }

    private static boolean containsCjk(String part) {
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if ((c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF)) return true;
        }
        return false;
    }
}
