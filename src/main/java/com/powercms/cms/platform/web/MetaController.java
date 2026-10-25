package com.powercms.cms.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 匿名可访问的应用元信息端点，用于前端和运维确认服务在线。
 */
@RestController
public class MetaController {

    private final String applicationName;

    public MetaController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    /**
     * 根路径 "/" 现在由 {@link SpaForwardController} 转发到 admin-ui 的 index.html，
     * 以支持 SPA 前端路由。应用元信息请使用此端点。
     */
    @GetMapping("/api/v1/meta")
    public Map<String, String> meta() {
        return Map.of(
            "name", applicationName,
            "product", "PowerCMS 12",
            "apiVersion", "v1"
        );
    }
}
