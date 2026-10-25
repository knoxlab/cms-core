package com.powercms.cms.delivery;

import com.powercms.cms.model.AnalyticsComponentSeedService;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitepackage.ComponentInstance;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 统计采集注入器（FR-STAT-001 注入开关）：检测页面组合中的
 * {@code system.analytics-tracker}（统计采集）组件，为交付页生成
 * analytics.js 引用与站点配置片段。
 *
 * <p>约定：每页最多注入一次（去重）；配置字段留空走同源默认值；
 * 生成片段不含用户可控 HTML（endpoint/scriptUrl 仅做属性转义），防注入。
 *
 * <p>站点应用（{@code /webapps}，React/Vue SPA）没有页面组合：只要站点还存有
 * 统计采集组件类型即视为启用（见 {@link #renderSiteAppTag}），删除该组件类型则整个应用不采集。
 */
@Component
public class AnalyticsInjector {

    public static final String DEFAULT_ENDPOINT = "/api/v1/delivery/analytics/collect";
    public static final String DEFAULT_SCRIPT_URL = "/api/v1/delivery/analytics/script.js";

    private final ContentTypeRepository typeRepo;
    private final SiteRepository siteRepo;

    public AnalyticsInjector(ContentTypeRepository typeRepo, SiteRepository siteRepo) {
        this.typeRepo = typeRepo;
        this.siteRepo = siteRepo;
    }

    public record TrackerConfig(String endpoint, String scriptUrl) {}

    /**
     * 页面组合中是否包含统计采集组件；命中时返回组件数据里的配置。
     * 仅接受本站 COMPONENT 类型的 EMBEDDED 实例；首个命中生效。
     */
    public Optional<TrackerConfig> findTracker(UUID siteId, Map<String, List<ComponentInstance>> zones) {
        if (zones == null || zones.isEmpty()) return Optional.empty();
        Optional<UUID> trackerType = typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId,
                AnalyticsComponentSeedService.ANALYTICS_TRACKER).stream().findFirst()
            .map(t -> t.getId());
        if (trackerType.isEmpty()) return Optional.empty();
        UUID typeId = trackerType.get();
        for (List<ComponentInstance> components : zones.values()) {
            if (components == null) continue;
            for (ComponentInstance component : components) {
                if (component.kind() != ComponentInstance.Kind.EMBEDDED
                    || typeId.equals(component.componentTypeId()) == false) continue;
                String endpoint = text(component.data(), "endpoint_s");
                String scriptUrl = text(component.data(), "script_url_s");
                return Optional.of(new TrackerConfig(endpoint, scriptUrl));
            }
        }
        return Optional.empty();
    }

    /**
     * 生成注入 HTML：script 标签 + 内联配置。
     *
     * <p>配置里的 siteKey 必须是站点的 {@code siteKey}（采集端按它解析站点），
     * 不是站点 UUID——两者仅在站点以 UUID 为键创建时恰好相同。
     */
    public String renderTag(UUID siteId, TrackerConfig config) {
        String endpoint = escapeAttr(config.endpoint() == null || config.endpoint().isBlank()
            ? DEFAULT_ENDPOINT : config.endpoint().trim());
        String scriptUrl = escapeAttr(config.scriptUrl() == null || config.scriptUrl().isBlank()
            ? DEFAULT_SCRIPT_URL : config.scriptUrl().trim());
        String siteKey = escapeAttr(siteRepo.findById(siteId)
            .map(site -> site.getSiteKey() == null ? "" : site.getSiteKey())
            .orElseGet(siteId::toString));
        return "<script>window.powercmsAnalyticsConfig={siteKey:\"" + siteKey
            + "\",endpoint:\"" + endpoint + "\",autoTrackSpa:true};</script>"
            + "<script src=\"" + scriptUrl + "\" defer></script>";
    }

    /**
     * 站点应用（SPA）注入片段：站点存有统计采集组件类型即启用（默认同源配置），
     * 类型被删除则返回空、应用不采集。调用方负责把片段注入到应用启动脚本之前，
     * 保证应用挂载时 {@code window.powercmsAnalytics} 已就绪。
     */
    public Optional<String> renderSiteAppTag(UUID siteId) {
        boolean enabled = !typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId,
            AnalyticsComponentSeedService.ANALYTICS_TRACKER).isEmpty();
        if (!enabled) return Optional.empty();
        return Optional.of(renderTag(siteId, new TrackerConfig(null, null)));
    }

    /** 注入到 </body> 之前；找不到闭合标签时追加到末尾。 */
    public static String injectBeforeBodyEnd(String html, String snippet) {
        if (html == null || html.isBlank()) return snippet;
        int idx = html.toLowerCase(java.util.Locale.ROOT).lastIndexOf("</body>");
        return idx >= 0 ? html.substring(0, idx) + snippet + html.substring(idx) : html + snippet;
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        if (value == null) return "";
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return "";
        // 仅允许站内相对路径或 http(s) 绝对地址，阻止 javascript: 等伪协议
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("/") || lower.startsWith("http://") || lower.startsWith("https://")) {
            return text.length() > 500 ? text.substring(0, 500) : text;
        }
        return "";
    }

    private static String escapeAttr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
