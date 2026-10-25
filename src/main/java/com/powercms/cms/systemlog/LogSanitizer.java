package com.powercms.cms.systemlog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志脱敏（FR-LOG-RT-007 / FR-LOG-RT-009 / NFR-LOG-007）：
 * <ul>
 *   <li>键值对脱敏：password / token / secret / credential / api[-_]?key / authorization /
 *       cookie 等敏感键（大小写不敏感），值整体替换为 {@code ***}；</li>
 *   <li>格式化 PII：手机号保留前三后四（{@code 138****5678}）、身份证号保留前六后四；</li>
 *   <li>Bearer / Basic 令牌整体掩码；</li>
 *   <li><b>AI 专项规则（G-03 / FR-LOG-RT-009）：</b>prompt / answer / completion 正文一律
 *       替换为 {@code ***}，仅允许记录长度、模型标识、耗时、结果分类等元信息 ——
 *       独立规则实现，优先级高于其他规则。</li>
 * </ul>
 *
 * <p>规则表为静态内置（首期）；{@link #rules()} 以有序 Map 暴露，供配置化扩展入口
 * （NFR-LOG-016 预留）与测试断言。写入侧与展示侧共用本类（FR-LOG-PM-004 双通道兜底）。</p>
 */
public final class LogSanitizer {

    /** 值整体掩码占位。 */
    public static final String MASK = "***";

    /**
     * AI 日志专项（FR-LOG-RT-009）：prompt/答案正文不得落日志，仅元信息可留。
     * 匹配 "prompt": "... / prompt=... / prompt: ... 等常见序列化形态。
     */
    private static final Pattern AI_BODY = Pattern.compile(
        "(?i)(\"?(prompt|answer|completion|messages)\"?\\s*[:=]\\s*)(\"|'|`)?([^\"'`\\n}]{0,4096})");

    /** 键值对敏感键（JSON / query / logfmt 通用）：值替换为 ***。 */
    private static final Pattern SENSITIVE_KV = Pattern.compile(
        "(?i)(\"?(password|passwd|pwd|token|secret|credential|api[-_]?key|access[-_]?key|"
            + "client[-_]?secret|authorization|cookie|session[-_]?id|set[-_]?cookie)\"?\\s*[:=]\\s*)"
            + "(\"|')?([^\"'&\\s,}\\]]{0,1024})");

    /** Bearer / Basic 授权头值。 */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");

    /** 手机号（中国大陆 11 位）：保留前三后四。 */
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])(1[3-9][0-9])([0-9]{4})([0-9]{4})(?![0-9])");

    /** 身份证号（18 位 / 15 位）：保留前六后四。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9Xx])([0-9]{6})[0-9]{8}([0-9]{3}[0-9Xx]|[0-9]{3})(?![0-9Xx])");

    private LogSanitizer() {}

    /**
     * 对任意日志文本执行全规则脱敏（写入侧与展示侧共用，FR-LOG-PM-004）。
     * 空值安全：null/blank 原样返回。
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        // 规则顺序即优先级：AI 正文最先（最严），其次授权头，再次键值对，最后格式化 PII
        result = maskAiBody(result);
        result = BEARER.matcher(result).replaceAll("$1 " + MASK);
        result = maskSensitiveKv(result);
        result = maskPii(result);
        return result;
    }

    /** FR-LOG-AC-007：Query 参数敏感键脱敏（参数名保留、值置 ***），输入为 raw query 串。 */
    public static String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return rawQuery;
        StringBuilder out = new StringBuilder(rawQuery.length());
        for (String pair : rawQuery.split("&")) {
            if (out.length() > 0) out.append('&');
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                out.append(pair);
                continue;
            }
            String key = pair.substring(0, eq);
            if (isSensitiveKey(key)) out.append(key).append('=').append(MASK);
            else out.append(pair);
        }
        return out.toString();
    }

    static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(java.util.Locale.ROOT).replace("\"", "");
        return k.contains("password") || k.contains("passwd") || k.contains("pwd")
            || k.contains("token") || k.contains("secret") || k.contains("credential")
            || k.contains("apikey") || k.contains("api_key") || k.contains("api-key")
            || k.equals("authorization") || k.equals("cookie") || k.equals("sessionid")
            || k.equals("session_id");
    }

    /** FR-LOG-RT-009：AI prompt/answer 正文掩码；$1（键部分）保留，值整体替换为 ***。 */
    private static String maskAiBody(String text) {
        Matcher m = AI_BODY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1))
                + (m.group(3) == null ? MASK : m.group(3) + MASK));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String maskSensitiveKv(String text) {
        Matcher m = SENSITIVE_KV.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = m.group(4);
            // 值已被前置规则（如 Bearer 掩码）处理时跳过，避免 "Authorization: Bearer ***" → "*** ***"：
            // 1) 值本身即 ***；2) 值为 scheme 词（Bearer/Basic）且其后紧跟 " ***"（BEARER 规则已掩码 token）
            boolean alreadyMasked = value != null && (value.contains(MASK)
                || text.regionMatches(m.end(), " " + MASK, 0, 1 + MASK.length()));
            String replacement = alreadyMasked
                ? m.group()
                : Matcher.quoteReplacement(m.group(1)) + (m.group(3) == null ? MASK : m.group(3) + MASK);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String maskPii(String text) {
        String result = MOBILE.matcher(text).replaceAll("$1****$3");
        result = ID_CARD.matcher(result).replaceAll("$1********$2");
        return result;
    }

    /** 有序规则清单（NFR-LOG-016 配置化扩展预留；键 = 规则名）。 */
    public static Map<String, String> rules() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("ai-body-mask", "FR-LOG-RT-009 prompt/answer/completion 正文掩码");
        ordered.put("bearer-token-mask", "Authorization Bearer/Basic 值掩码");
        ordered.put("sensitive-kv-mask", "password/token/secret/credential/api-key/authorization/cookie 键值掩码");
        ordered.put("mobile-partial-mask", "手机号保留前三后四");
        ordered.put("id-card-partial-mask", "身份证号保留前六后四");
        return ordered;
    }

    /** 常见敏感键样例（测试与文档用）。 */
    public static List<String> sensitiveKeys() {
        return List.of("password", "token", "secret", "credential", "apiKey", "authorization", "cookie");
    }
}
