package com.powercms.cms.identity.permission;

import com.powercms.cms.identity.CidrMatcher;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * 离线地域库：每行 {@code CIDR,国家,省级}。支持 gzip。未命中返回 empty，调用方拒绝。
 * 文件由 {@code powercms.access.geo-db} 指向。行数超过 {@link #INDEX_THRESHOLD} 时按起始地址二分，
 * 要求区间互不重叠（全国库由构建脚本保证）。
 */
public final class PrefixGeoResolver implements GeoResolver {

    static final int INDEX_THRESHOLD = 4096;

    private final List<Row> linear;
    private final long[] starts;
    private final long[] ends;
    private final String[] countries;
    private final String[] provinces;

    private PrefixGeoResolver(List<Row> linear) {
        this.linear = linear;
        this.starts = null;
        this.ends = null;
        this.countries = null;
        this.provinces = null;
    }

    private PrefixGeoResolver(long[] starts, long[] ends, String[] countries, String[] provinces) {
        this.linear = null;
        this.starts = starts;
        this.ends = ends;
        this.countries = countries;
        this.provinces = provinces;
    }

    public int size() {
        return linear != null ? linear.size() : starts.length;
    }

    public static PrefixGeoResolver load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    public static PrefixGeoResolver load(InputStream in) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        InputStream data = (first == 0x1f && second == 0x8b) ? new GZIPInputStream(buffered) : buffered;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(data, StandardCharsets.UTF_8))) {
            return load(reader);
        }
    }

    public static PrefixGeoResolver load(Reader reader) throws IOException {
        BufferedReader lines = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
        List<Row> rows = new ArrayList<>();
        String line;
        while ((line = lines.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split(",", 3);
            if (parts.length < 2 || !CidrMatcher.isWellFormed(parts[0].trim())) continue;
            String province = parts.length > 2 ? parts[2].trim() : "";
            rows.add(new Row(parts[0].trim(), parts[1].trim(), province));
        }
        if (rows.size() <= INDEX_THRESHOLD) {
            return new PrefixGeoResolver(List.copyOf(rows));
        }
        return index(rows);
    }

    private static PrefixGeoResolver index(List<Row> rows) {
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingLong(i -> span(rows.get(i).cidr())[0]));
        long[] starts = new long[rows.size()];
        long[] ends = new long[rows.size()];
        String[] countries = new String[rows.size()];
        String[] provinces = new String[rows.size()];
        for (int i = 0; i < order.length; i++) {
            Row row = rows.get(order[i]);
            long[] span = span(row.cidr());
            starts[i] = span[0];
            ends[i] = span[1];
            countries[i] = row.country();
            provinces[i] = row.province();
        }
        return new PrefixGeoResolver(starts, ends, countries, provinces);
    }

    @Override
    public Optional<Place> locate(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return Optional.empty();
        if (linear != null) {
            for (Row row : linear) {
                if (CidrMatcher.isAllowed(row.cidr(), clientIp)) {
                    return Optional.of(new Place(row.country(), row.province()));
                }
            }
            return Optional.empty();
        }
        Long ip = ipv4(normalize(clientIp));
        if (ip == null) return Optional.empty();
        int lo = 0;
        int hi = starts.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= ip) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found >= 0 && ip <= ends[found]) {
            return Optional.of(new Place(countries[found], provinces[found]));
        }
        return Optional.empty();
    }

    private static long[] span(String cidr) {
        int slash = cidr.indexOf('/');
        String body = slash < 0 ? cidr : cidr.substring(0, slash);
        int prefix = slash < 0 ? 32 : Integer.parseInt(cidr.substring(slash + 1));
        long network = ipv4(body);
        long mask = prefix == 0 ? 0L : (0xFFFF_FFFFL << (32 - prefix)) & 0xFFFF_FFFFL;
        long start = network & mask;
        long end = start | (~mask & 0xFFFF_FFFFL);
        return new long[] {start, end};
    }

    private static String normalize(String ip) {
        String value = ip.trim();
        if ("https://example.net/id/garnet".equals(value) || "::1".equals(value)) return "127.0.0.1";
        if (value.startsWith("::ffff:")) return value.substring(7);
        return value;
    }

    private static Long ipv4(String ip) {
        if (ip == null) return null;
        String[] octets = ip.split("\\.");
        if (octets.length != 4) return null;
        long value = 0;
        try {
            for (String octet : octets) {
                int n = Integer.parseInt(octet);
                if (n < 0 || n > 255) return null;
                value = (value << 8) | n;
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return value & 0xFFFF_FFFFL;
    }

    private record Row(String cidr, String country, String province) {}
}
