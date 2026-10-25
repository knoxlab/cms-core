package com.powercms.cms.stats;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 客户端识别与隐私脱敏（FR-STAT-003、FR-STAT-101、NFR-STAT-001）：
 * - IP 匿名化：IPv4 末段置零 / IPv6 后 80 位置零，再以站点级盐值 SHA-256 哈希；
 * - X-Forwarded-For 还原：按可信代理层数从右侧倒数第 N 跳取客户端 IP；
 * - UA 解析：轻量规则（OS/浏览器/设备类型），解析失败归「未知」。
 */
public final class ClientFingerprint {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern BOT = Pattern.compile("(?i)(bot|crawler|spider|crawling|headless|curl|wget|python-requests)");

    private ClientFingerprint() {}

    /** XFF 还原：从右往左跳过 trustedProxies 跳，剩余最右者即客户端 IP；跳数不足返回直连地址。 */
    public static String resolveClientIp(String xForwardedFor, String remoteAddr, int trustedProxies) {
        if (xForwardedFor == null || xForwardedFor.isBlank() || trustedProxies <= 0) {
            return remoteAddr;
        }
        String[] hops = xForwardedFor.split(",");
        int usable = hops.length - trustedProxies;
        if (usable <= 0) {
            return remoteAddr;
        }
        String candidate = hops[usable - 1].trim();
        return candidate.isEmpty() ? remoteAddr : candidate;
    }

    /** 末段置零明文（展示用，ip_masked）：IPv4 末段置零；IPv6 归 ::/64 前缀。 */
    public static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        // 回环地址（本机自测/开发流量）没有匿名化价值：末段置零会显示成 127.0.0.0 / ::0
        // 这类「看着像坏了」的地址，反而让运维分不清是采集异常还是本机访问，原样保留。
        if ("::1".equals(ip) || (IPV4.matcher(ip).matches() && ip.startsWith("127."))) return ip;
        if (IPV4.matcher(ip).matches()) {
            int cut = ip.lastIndexOf('.');
            return ip.substring(0, cut + 1) + "0";
        }
        int cut = ip.indexOf("::");
        if (cut >= 0) return ip.substring(0, cut + 2) + "0";
        String[] groups = ip.split(":", -1);
        if (groups.length >= 4) {
            return String.join(":", groups[0], groups[1], groups[2], groups[3]) + "::0";
        }
        return "unknown";
    }

    /** 盐值 SHA-256 哈希（存储/检索用，ip_hash）。 */
    public static String hashIp(String ip, String siteSalt) {
        return sha256(maskIp(ip) + "|" + siteSalt);
    }

    /** 访客去重键（第一方 visitor_id 为主，会员或缺失场景退化为 IP 哈希）。 */
    public static String visitorHash(String visitorId, String ipHash) {
        return visitorId == null || visitorId.isBlank() ? ipHash : visitorId;
    }

    public static boolean isBot(String userAgent) {
        return userAgent != null && BOT.matcher(userAgent).find();
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** UA 解析结果。 */
    public record UaInfo(String os, String browser, String browserVersion, String deviceType) {}

    public static UaInfo parseUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return new UaInfo("未知", "未知", null, "unknown");
        String os = detectOs(ua);
        String[] browser = detectBrowser(ua);
        String device = detectDevice(ua);
        return new UaInfo(os, browser[0], browser[1], device);
    }

    private static String detectOs(String ua) {
        String lower = ua.toLowerCase();
        if (lower.contains("windows nt")) return "Windows";
        if (lower.contains("android")) return "Android";
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) return "iOS";
        if (lower.contains("mac os x") || lower.contains("macintosh")) return "macOS";
        if (lower.contains("linux")) return "Linux";
        return "未知";
    }

    private static String[] detectBrowser(String ua) {
        String lower = ua.toLowerCase();
        // 顺序敏感：Edg/OPR 必须先于 Chrome；版本取 token 形如 "xx/123"
        if (lower.contains("edg/")) return new String[]{"Edge", version(ua, "Edg/")};
        if (lower.contains("opr/") || lower.contains("opera")) return new String[]{"Opera", version(ua, "OPR/")};
        if (lower.contains("chrome/")) return new String[]{"Chrome", version(ua, "Chrome/")};
        if (lower.contains("firefox/")) return new String[]{"Firefox", version(ua, "Firefox/")};
        if (lower.contains("safari/") && lower.contains("version/")) return new String[]{"Safari", version(ua, "Version/")};
        return new String[]{"未知", null};
    }

    private static String version(String ua, String marker) {
        int idx = ua.indexOf(marker);
        if (idx < 0) return null;
        String rest = ua.substring(idx + marker.length());
        StringBuilder sb = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c) || c == '.') sb.append(c);
            else break;
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String detectDevice(String ua) {
        String lower = ua.toLowerCase();
        if (lower.contains("ipad") || (lower.contains("android") && !lower.contains("mobile"))) return "tablet";
        if (lower.contains("mobile") || lower.contains("iphone")) return "mobile";
        return "desktop";
    }
}
