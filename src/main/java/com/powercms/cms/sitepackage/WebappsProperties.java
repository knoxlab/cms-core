package com.powercms.cms.sitepackage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 站点应用（React/Vue 前端）部署配置（{@code powercms.webapps.*}）。
 *
 * <p>站点包导入时，若模板运行时为 REACT/VUE，其前端产物默认部署到<b>当前运行目录</b>下的
 * {@code webapps/{siteKey}/}，由 {@code SiteAppController} 直接对外服务（可直接访问）；
 * 浏览器访问该应用时，服务端在 {@code index.html} 注入站点标识与 API 根路径，
 * 应用无需手工配置 {@code ?siteId=} 即可自动匹配站点 API 路径。</p>
 */
@Component
@ConfigurationProperties(prefix = "powercms.webapps")
public class WebappsProperties {

    /** 前端产物缺失时的构建策略。 */
    public enum BuildMode {
        /** 包内无构建产物时按清单 entrypoints 构建（默认）。 */
        AUTO,
        /** 从不构建（仅部署包内既有产物；离线/无 Node 环境用）。 */
        NEVER,
        /** 总是重新构建（忽略包内产物）。 */
        ALWAYS
    }

    /** 部署根目录。相对路径以进程工作目录为基准（默认 {@code ./webapps}）。 */
    private String root = "webapps";

    /** 总开关：关闭后导入不再落盘站点应用，也不提供 {@code /webapps/**} 访问。 */
    private boolean enabled = true;

    /** 构建策略。 */
    private BuildMode build = BuildMode.AUTO;

    /** 构建超时（秒）。 */
    private long buildTimeoutSeconds = 600;

    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public BuildMode getBuild() { return build; }
    public void setBuild(BuildMode build) { this.build = build; }
    public long getBuildTimeoutSeconds() { return buildTimeoutSeconds; }
    public void setBuildTimeoutSeconds(long buildTimeoutSeconds) { this.buildTimeoutSeconds = buildTimeoutSeconds; }
}
