package com.powercms.cms.publish;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 静态发布兜底（FR-ACCESS-011）：历史 Release 目录不对外，未产出路由不直出文件；
 * 回滚后按当前匿名公开档删掉已不再允许的页面文件。
 */
@Component
public class StaticPublishGuard {

    private final ContentItemRepository items;
    private final ObjectPermissionService permissions;
    private final String ssrBaseUrl;
    private final com.powercms.cms.identity.permission.ProgramAccessService programAccess;

    StaticPublishGuard(ContentItemRepository items, ObjectPermissionService permissions,
                       @Value("${powercms.delivery.ssr-base-url:}") String ssrBaseUrl) {
        this(items, permissions, ssrBaseUrl, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    StaticPublishGuard(ContentItemRepository items, ObjectPermissionService permissions,
                       @Value("${powercms.delivery.ssr-base-url:}") String ssrBaseUrl,
                       com.powercms.cms.identity.permission.ProgramAccessService programAccess) {
        this.items = items;
        this.permissions = permissions;
        this.ssrBaseUrl = ssrBaseUrl == null ? "" : ssrBaseUrl.trim();
        this.programAccess = programAccess;
    }

    /** 写入 Nginx 片段，并清掉该产物目录里当前匿名不可见的页面。 */
    public int seal(Path releaseDir, java.util.UUID siteId) throws IOException {
        Path base = releaseDir.getParent().getParent();
        Path rules = base.resolve("nginx-delivery.conf");
        Files.writeString(rules, snippet(siteId), StandardCharsets.UTF_8);
        String includePath = rules.toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(base.resolve("nginx.server.conf"), "include " + includePath + ";\n", StandardCharsets.UTF_8);
        return scrub(releaseDir, siteId);
    }

    public int scrub(Path releaseDir, java.util.UUID siteId) throws IOException {
        if (releaseDir == null || !Files.isDirectory(releaseDir)) return 0;
        int removed = 0;
        try (Stream<Path> walk = Files.walk(releaseDir)) {
            for (Path file : walk.filter(p -> p.getFileName() != null && "index.html".equals(p.getFileName().toString())).toList()) {
                String route = routeOf(releaseDir, file);
                if (route == null || anonymouslyVisible(siteId, route)) continue;
                Files.deleteIfExists(file);
                removed++;
            }
        }
        return removed;
    }

    private boolean anonymouslyVisible(java.util.UUID siteId, String route) {
        return items.findBySiteIdAndRoutePathAndStatus(siteId, route, ContentItemEntity.STATUS_PUBLISHED)
            .map(item -> permissions.hasAnonymousRight(ObjectType.CONTENT, item.getId(), ObjectType.VIEW)
                && (programAccess == null || !programAccess.variesByRequest(ObjectType.CONTENT, item.getId())))
            .orElse(false);
    }

    static String routeOf(Path releaseDir, Path indexHtml) {
        Path rel = releaseDir.relativize(indexHtml.getParent());
        if (rel.toString().isBlank() || ".".equals(rel.toString())) return "/";
        String route = "/" + rel.toString().replace('\\', '/');
        if (route.contains("..")) return null;
        return route;
    }

    String snippet(java.util.UUID siteId) {
        String fallback = ssrBaseUrl.isBlank()
            ? "return 404;"
            : "proxy_pass " + stripSlash(ssrBaseUrl) + "/api/v1/delivery/sites/" + siteId + "/pages$is_args$args;";
        return """
            # PowerCMS FR-ACCESS-011：只暴露 current 指向的产物。历史 releases/ 不得作为站点根。
            # 将本文件 include 到站点 server，并把 root 指到 current 解析后的目录。
            location ^~ /releases/ { return 404; }
            location / {
                try_files $uri $uri/index.html @powercms_ssr;
            }
            location @powercms_ssr {
                %s
            }
            """.formatted(fallback);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
