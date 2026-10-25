package com.powercms.cms.identity;

/**
 * IPv4 CIDR / exact-address matcher for API Token {@code allowed_from}.
 */
public final class CidrMatcher {

    private CidrMatcher() {}

    public static boolean isAllowed(String allowedFrom, String ip) {
        if (allowedFrom == null || allowedFrom.isBlank()) return true;
        String client = normalizeIp(ip);
        if (client == null) return false;
        for (String part : allowedFrom.split(",")) {
            String cidr = part.trim();
            if (!cidr.isEmpty() && matches(cidr, client)) return true;
        }
        return false;
    }

    static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        String value = ip.trim();
        if ("https://example.net/id/garnet".equals(value) || "::1".equals(value)) return "127.0.0.1";
        if (value.startsWith("::ffff:")) return value.substring(7);
        return value;
    }

    static boolean matches(String cidr, String ip) {
        if (cidr.equals(ip)) return true;
        int slash = cidr.indexOf('/');
        if (slash < 0) return false;
        try {
            long address = ipv4(ip);
            long network = ipv4(cidr.substring(0, slash));
            int prefix = Integer.parseInt(cidr.substring(slash + 1));
            if (prefix < 0 || prefix > 32) return false;
            long mask = prefix == 0 ? 0L : (0xFFFF_FFFFL << (32 - prefix)) & 0xFFFF_FFFFL;
            return (address & mask) == (network & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long ipv4(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) throw new IllegalArgumentException(ip);
        long value = 0;
        for (String octet : octets) {
            int n = Integer.parseInt(octet);
            if (n < 0 || n > 255) throw new IllegalArgumentException(ip);
            value = (value << 8) | n;
        }
        return value & 0xFFFF_FFFFL;
    }

    /** 逗号分隔的精确 IPv4 或 CIDR。空列表视为合法（表示不限制）。 */
    public static boolean isWellFormed(String allowedFrom) {
        if (allowedFrom == null || allowedFrom.isBlank()) return true;
        for (String part : allowedFrom.split(",")) {
            String cidr = part.trim();
            if (cidr.isEmpty()) continue;
            if (!wellFormedEntry(cidr)) return false;
        }
        return true;
    }

    private static boolean wellFormedEntry(String cidr) {
        String body = cidr;
        if (cidr.indexOf('/') >= 0) {
            int slash = cidr.indexOf('/');
            body = cidr.substring(0, slash);
            try {
                int prefix = Integer.parseInt(cidr.substring(slash + 1));
                if (prefix < 0 || prefix > 32) return false;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        try {
            ipv4(body);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
