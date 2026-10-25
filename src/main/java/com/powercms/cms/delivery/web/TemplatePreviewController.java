package com.powercms.cms.delivery.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.delivery.FreeMarkerSandbox;
import com.powercms.cms.delivery.TemplatePreviewTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * 一次性模板预览端点（FR-AGT-TPL-004）。
 *
 * <p>{@code GET /api/v1/preview/template?token=...}：Token 仅可消费一次，渲染服务端在签发时
 * 保存的模板快照（源码 + 样例数据），**不接收客户端回传的模板内容**；渲染走
 * {@link FreeMarkerSandbox}（SAFER_RESOLVER、关闭 ?api）的预览模式，可诊断行号。
 * 预览不落库、不影响线上，响应禁止缓存并施加 CSP {@code sandbox} 抑制脚本执行。
 */
@RestController
@RequestMapping("/api/v1/preview")
class TemplatePreviewController {

    private final TemplatePreviewTokenService tokens;
    private final FreeMarkerSandbox sandbox;
    private final ObjectMapper objectMapper;
    private final String allowedHost;

    TemplatePreviewController(TemplatePreviewTokenService tokens, FreeMarkerSandbox sandbox,
                              ObjectMapper objectMapper,
                              @Value("${powercms.preview.allowed-host:}") String allowedHost) {
        this.tokens = tokens;
        this.sandbox = sandbox;
        this.objectMapper = objectMapper;
        this.allowedHost = allowedHost == null ? "" : allowedHost.trim();
    }

    @GetMapping(value = "/template", produces = "text/html")
    ResponseEntity<String> template(@RequestParam String token, HttpServletRequest request) {
        if (!allowedHost.isBlank() && !request.getServerName().equalsIgnoreCase(normalizeHost(allowedHost))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        TemplatePreviewTokenService.Snapshot snapshot = tokens.consume(token);
        Map<String, Object> model = sampleModel(snapshot.sampleDataJson());
        String html = sandbox.render(snapshot.templateName(), snapshot.source(), model, true);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy",
                "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data: https: 'self'")
            .header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(html);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sampleModel(String sampleDataJson) {
        if (sampleDataJson == null || sampleDataJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> model = objectMapper.readValue(sampleDataJson,
                new TypeReference<Map<String, Object>>() {});
            return model == null ? Map.of() : model;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String normalizeHost(String configured) {
        String value = configured.trim();
        try {
            URI uri = value.contains("://") ? URI.create(value) : URI.create("//" + value);
            if (uri.getHost() == null) throw new IllegalArgumentException();
            return uri.getHost();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("powercms.preview.allowed-host must be a host or absolute URL", ex);
        }
    }
}
