package com.powercms.cms.media;

import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * 远端媒体拉取（FR-AGT-MEDIA-002 的 URL 来源通道）：Agent 只给 URL，由服务端拉取后转交 Knox。
 *
 * <p>该通道是典型的 SSRF 面，安全口径与发布健康检查一致（{@code DeploymentHealthChecker}）：
 * 仅允许 http/https、禁止携带 userInfo、逐一解析 DNS 结果并拒绝任意本地/内网/多播地址
 * （可通过 {@code powercms.media.remote-fetch.allow-private=true} 在可信内网显式放开）、
 * 不自动跟随重定向（避免「校验 A 跳转 B」绕过）、连接/整包超时、按上限流式读取。
 */
@Component
public class MediaRemoteFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final boolean allowPrivate;
    private final HttpClient http;

    public MediaRemoteFetcher(
            @Value("${powercms.media.remote-fetch.allow-private:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
        this.http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /** 拉取结果：字节 + 推断文件名 + 上游 Content-Type + 来源 URL（写入版权/来源审计）。 */
    public record Fetched(byte[] bytes, String filename, String contentType, String sourceUrl) {}

    public Fetched fetch(String rawUrl, long maxBytes) {
        URI uri = parse(rawUrl);
        guardAddress(uri.getHost());
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "*/*")
            .GET()
            .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodes.MEDIA_UPSTREAM_UNAVAILABLE,
                "远端媒体拉取失败：" + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCodes.MEDIA_UPSTREAM_UNAVAILABLE, "远端媒体拉取被中断");
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new BusinessException(ErrorCodes.MEDIA_UPSTREAM_UNAVAILABLE,
                "远端媒体返回重定向（" + status + "），出于 SSRF 防护不予跟随，请提供最终地址");
        }
        if (status != 200) {
            throw new BusinessException(ErrorCodes.MEDIA_UPSTREAM_UNAVAILABLE,
                "远端媒体返回 HTTP " + status);
        }
        byte[] bytes = readCapped(response.body(), maxBytes);
        String headerType = response.headers().firstValue("content-type").orElse(null);
        String contentType = headerType == null || headerType.isBlank()
            ? null : headerType.trim();
        String filename = response.headers().firstValue("content-disposition").orElse(null);
        return new Fetched(bytes, filenameOf(uri, filename), contentType, rawUrl);
    }

    private URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "source_url 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "source_url 不是合法 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "source_url 仅支持 http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "source_url 须为不含凭据的绝对地址");
        }
        return uri;
    }

    private void guardAddress(String host) {
        if (allowPrivate) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodes.MEDIA_UPSTREAM_UNAVAILABLE, "source_url 域名无法解析");
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "source_url 指向内网地址，已拒绝（如需放开请配置 powercms.media.remote-fetch.allow-private=true）");
            }
        }
    }

    private static boolean isPrivate(InetAddress a) {
        return a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
            || a.isSiteLocalAddress() || a.isMulticastAddress();
    }

    private static byte[] readCapped(InputStream in, long maxBytes) {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new BusinessException(ErrorCodes.MEDIA_FILE_TOO_LARGE,
                        "远端媒体超过大小上限（" + maxBytes + " 字节）");
                }
                out.write(buffer, 0, read);
            }
            if (out.size() == 0) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "远端媒体内容为空");
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodes.MEDIA_UPSTREAM_UNAVAILABLE,
                "远端媒体读取失败：" + ex.getMessage());
        }
    }

    /** 从 URL 推断文件名（dry-run 阶段无法访问上游时使用；非法 URL 返回 null）。 */
    public static String filenameOfUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            return filenameOf(URI.create(rawUrl.trim()), null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 文件名推断优先级：Content-Disposition → URL 末段 → 兜底；统一剥掉路径分隔符避免目录穿越。 */
    public static String filenameOf(URI uri, String contentDisposition) {
        String candidate = null;
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            String lower = contentDisposition.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("filename=");
            if (idx >= 0) {
                candidate = contentDisposition.substring(idx + "filename=".length()).trim();
                if (candidate.startsWith("\"")) {
                    int end = candidate.indexOf('"', 1);
                    candidate = end > 0 ? candidate.substring(1, end) : candidate.substring(1);
                } else {
                    int semi = candidate.indexOf(';');
                    if (semi > 0) {
                        candidate = candidate.substring(0, semi);
                    }
                }
            }
        }
        if (candidate == null || candidate.isBlank()) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            int slash = path.lastIndexOf('/');
            candidate = slash >= 0 ? path.substring(slash + 1) : path;
            try {
                candidate = URLDecoder.decode(candidate, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 保留原值，交由后续扩展名校验给出结论
            }
        }
        if (candidate == null || candidate.isBlank()) {
            return "download.bin";
        }
        String sanitized = candidate.replace('\\', '/');
        int lastSlash = sanitized.lastIndexOf('/');
        if (lastSlash >= 0) {
            sanitized = sanitized.substring(lastSlash + 1);
        }
        sanitized = sanitized.replace("\"", "").trim();
        return sanitized.isEmpty() ? "download.bin" : sanitized;
    }
}
