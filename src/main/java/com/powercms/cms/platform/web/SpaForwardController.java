package com.powercms.cms.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * SPA catch-all 控制器：将非 API、非静态资源的 GET 请求转发到 index.html，
 * 以支持 React Router 的客户端路由。
 * <p>
 * 匹配顺序：Spring MVC 优先匹配更具体的路径（如 /api/v1/meta），
 * 只有当没有其他控制器匹配时才会进入此 catch-all。
 * <p>
 * DEF-11-11：客户端路由不只有单段（/login、/content），还有多段
 * （/interaction/review、/analytics/overview、/pages/editor、/containers/x）。原先只映射
 * {@code /{path}}，多段深链会 404；这里与 {@link SpaShellRequestMatcher} 共用同一套规则：
 * 属 SPA 壳的转发 index.html，属后端保留前缀（api/media/v3…）的显式 404，不返回 HTML。
 */
@Controller
public class SpaForwardController {

    /**
     * 根路径 → 前端 index.html
     */
    @GetMapping("/")
    public String forwardRoot() {
        return "forward:/index.html";
    }

    /**
     * 未匹配的客户端路由（单段与多段）→ 前端 index.html（SPA catch-all）
     * <p>
     * 注意：此映射优先级低于具体的 @RestController 映射（字面量/更精确的模板优先）。
     * <p>
     * OBS-MSRC-03：首段必须排除静态资源前缀（{@link AdminUiWebConfig#NOT_STATIC_PREFIX}）。
     * 此前模式只约束了前两段不含 {@code .}，于是 {@code /assets/nested/chunk.js} 这类**嵌套静态资源**
     * 会被本 catch-all 抢先匹配（RequestMappingHandlerMapping 优先级高于资源处理器映射），
     * 又因落在保留前缀下走 404 分支，导致磁盘上真实存在的文件被遮蔽成 404。
     */
    @GetMapping({
        "/{p1:" + AdminUiWebConfig.NOT_STATIC_PREFIX + "[^\\.]*}",
        "/{p1:" + AdminUiWebConfig.NOT_STATIC_PREFIX + "[^\\.]*}/{p2:[^\\.]*}",
        "/{p1:" + AdminUiWebConfig.NOT_STATIC_PREFIX + "[^\\.]*}/{p2:[^\\.]*}/**"
    })
    public String forwardSpa(HttpServletRequest request) throws NoResourceFoundException {
        if (!SpaShellRequestMatcher.isShellPath(request.getRequestURI())) {
            // 保留前缀（API/媒体/文档等）下的未知路径：保持 404 语义（统一错误体），不能回退成 HTML。
            // 用 NoResourceFoundException 而非 ResponseStatusException——后者会被兜底处理器当作
            // 未处理异常记 ERROR 并返回 500（DEF-11-11 回归探针发现）。
            throw new NoResourceFoundException(HttpMethod.GET, request.getRequestURI());
        }
        return "forward:/index.html";
    }
}
