package com.powercms.cms.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 将 admin-ui 的构建产物（admin-ui/dist）作为静态资源对外提供。
 *
 * <p>保持 spring.web.resources.add-mappings=false 不变（仅禁用 Spring Boot 默认映射），
 * 通过自定义 resource handler 精确控制 admin-ui 的资源服务。</p>
 *
 * <p><b>两级来源，顺序即优先级：</b></p>
 * <ol>
 *   <li><b>外部目录</b>（可覆盖）：系统属性/环境变量 {@code powercms.admin-ui.dist} /
 *       {@code POWERCMS_ADMIN_UI_DIST} 指定的目录；未指定时从工作目录向上探测
 *       {@code admin-ui/dist}。存在即优先生效——开发期 {@code npm run build} 后无需重新
 *       编译后端，也支持不重打包热替换前端；</li>
 *   <li><b>内嵌 classpath</b>：{@code classpath:/admin-ui/dist/}。构建期由 cms-core pom 的
 *       {@code resources} 配置把 {@code admin-ui/dist} 拷入 {@code target/classes}，
 *       随 jar 与 GraalVM 原生二进制一起发布——外部目录缺失（典型：原生二进制独立部署）
 *       时自动回退，二进制开箱即可完整服务管理端前端。内嵌与否受原生可达性配置
 *       {@code resource-config.json} 的 {@code admin-ui/dist/.*} 约束。</li>
 * </ol>
 */
@Configuration
public class AdminUiWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AdminUiWebConfig.class);

    /**
     * 内嵌管理端产物的 classpath 根（构建期拷入 target/classes；原生镜像内注册为可达资源）。
     * 末尾斜杠是资源位置（resource location）的规范形式。
     */
    public static final String CLASSPATH_DIST_LOCATION = "classpath:/admin-ui/dist/";

    /**
     * 构建产物目录。**实例字段**（非 static）：解析结果只在“注册资源处理器”时使用，而每次
     * 应用上下文启动都会重新注册一次；若做成 static 单例，则同一 JVM 内后启动的上下文会沿用
     * 第一次解析的路径，测试无法用 {@code -Dpowercms.admin-ui.dist} 指向临时目录
     * （OBS-MSRC-03 的回归用例即因此在全量跑时被前一个上下文“锁死”）。
     */
    private final Path distDir = resolveDistDir();

    /**
     * 静态资源 URL 前缀（不含斜杠）。资源处理器与 SPA catch-all（{@link SpaForwardController}）
     * 共同依赖此常量：catch-all 必须把该前缀整体让给资源处理器，否则嵌套目录下的真实文件会被
     * 遮蔽成 404（OBS-MSRC-03）。
     */
    public static final String STATIC_PREFIX = "assets";

    /**
     * 供 {@code @GetMapping} 复用的「非静态前缀」路径段正则。
     * <p>PathPattern 的 {@code {name:regex}} 逐段整体匹配，故用末位断言排除整段：
     * 首段为静态前缀的请求不再进入 catch-all，从而落到 {@code /assets/**} 资源处理器。
     * 注解值必须是编译期常量，故在此拼接。</p>
     */
    public static final String NOT_STATIC_PREFIX = "(?!(?:" + STATIC_PREFIX + ")$)";

    private static Path resolveDistDir() {
        // 显式覆盖优先（可用于 CI/生产环境指定前端目录）。指向不存在的路径时不回退探测，
        // 由资源处理器的多位置链自然落到内嵌 classpath——错误覆盖得到的是内嵌前端而非随机目录。
        String override = System.getProperty("powercms.admin-ui.dist",
                System.getenv("POWERCMS_ADMIN_UI_DIST"));
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }

        // 默认：从工作目录向上查找包含 admin-ui/dist 的项目根目录（开发/源码运行场景）
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path probe = cwd;
        for (int i = 0; i < 10; i++) {
            Path candidate = probe.resolve("admin-ui").resolve("dist");
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
            probe = probe.getParent();
            if (probe == null) break;
        }

        // 找不到也返回该路径：目录不存在时资源处理器回退到内嵌 classpath（见类注释）
        return cwd.resolve("admin-ui").resolve("dist");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String distLocation = distDir.toUri().toString();
        boolean externalExists = distDir.toFile().isDirectory();
        boolean embeddedExists = getClass().getResource("/admin-ui/dist/index.html") != null;
        if (externalExists) {
            log.info("管理端前端来源：外部目录 {}（覆盖内嵌）", distDir);
        } else if (embeddedExists) {
            log.info("管理端前端来源：内嵌 classpath:/admin-ui/dist/");
        } else {
            log.warn("管理端前端缺失：外部目录 {} 不存在，且构建期未内嵌 classpath:/admin-ui/dist/"
                    + "（是否未执行 admin-ui 构建？）。管理端页面与静态资源将不可用", distDir);
        }

        // admin-ui 静态资源（JS/CSS/图片等）——Vite 构建的文件名含 hash，可长期缓存
        // 注意：前缀同时约束着 SpaForwardController 的 catch-all 排除项（OBS-MSRC-03）。
        // 位置顺序 = 优先级：外部目录（覆盖）在前，内嵌 classpath 在后（外部缺失的文件逐个回退）。
        registry.addResourceHandler("/" + STATIC_PREFIX + "/**")
                .addResourceLocations(distLocation + "assets/", CLASSPATH_DIST_LOCATION + "assets/")
                .setCachePeriod(365 * 24 * 3600)
                .resourceChain(true);

        // index.html 本身（SPA 入口，不缓存）
        registry.addResourceHandler("/index.html")
                .addResourceLocations(distLocation, CLASSPATH_DIST_LOCATION)
                .setCachePeriod(0);

        // favicon 等根级静态文件
        registry.addResourceHandler("/favicon.svg", "/favicon.ico", "/vite.svg")
                .addResourceLocations(distLocation, CLASSPATH_DIST_LOCATION);
    }
}
