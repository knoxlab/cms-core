package com.powercms.cms.sitepackage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 站点应用部署（{@code webapps/{siteKey}/}）。
 *
 * <p>站点包导入时把 React/Vue 前端产物写入 {@link WebappsProperties#getRoot()}（默认当前运行目录的
 * {@code webapps}）下的 {@code {siteKey}} 子目录，由 {@code SiteAppController} 直接对外服务。
 * 产物来源两级：</p>
 * <ol>
 *   <li><b>包内构建产物</b>：{@code template/{entrypoints.output}/&#42;&#42;}（如 {@code template/dist/&#42;&#42;}），
 *       打包时已 {@code npm run build} 的成品直接部署（快路径）；</li>
 *   <li><b>现场构建</b>（{@link WebappsProperties.BuildMode#AUTO}，包内无产物时）：把 {@code template/&#42;&#42;}
 *       落到临时工程目录，执行清单 {@code entrypoints.build}（白名单校验 + 参数数组方式执行，
 *       不经 shell），产物目录同 {@code entrypoints.output}。</li>
 * </ol>
 *
 * <p>安全边界：输出路径逐段校验（拒绝绝对路径、盘符/ADS、{@code ..}、保留设备名），归一化后必须
 * 仍在站点目录内；构建命令复用 {@link SitePackageProperties#getAllowedEntrypointPrefixes()} 白名单，
 * 与 FR-PACKAGE-003 的入口约束同源。</p>
 */
@Service
public class WebappsService {

    private static final Logger log = LoggerFactory.getLogger(WebappsService.class);

    private static final int MAX_OUTPUT_FILES = 20_000;
    private static final long MAX_OUTPUT_BYTES = 512L * 1024 * 1024;

    private final WebappsProperties props;
    private final SitePackageProperties pkgProps;

    public WebappsService(WebappsProperties props, SitePackageProperties pkgProps) {
        this.props = props;
        this.pkgProps = pkgProps;
    }

    /** 部署结果。{@code path} 为相对部署根的目录（即 {@code {siteKey}}）。 */
    public record DeployResult(String siteKey, String path, int fileCount, long sizeBytes, String source) {}

    public boolean isEnabled() { return props.isEnabled(); }

    /** 部署根目录（绝对路径；相对配置以进程工作目录为基准）。 */
    public Path root() {
        return Path.of(props.getRoot()).toAbsolutePath().normalize();
    }

    /** 站点应用目录（未必存在）。 */
    public Path siteDir(String siteKey) {
        return root().resolve(siteKey).normalize();
    }

    /**
     * 从站点包条目部署站点应用。
     *
     * @param runtimeType 清单 templateRuntime.type（仅 REACT/VUE 部署）
     * @param entrypoints 清单 entrypoints（取 {@code output} 作为产物目录，默认 dist）
     * @param entries     解包后的全部条目（{@code template/...} 键）
     * @return 部署结果；关闭开关、非 REACT/VUE 或无产物可部署时为空
     */
    public Optional<DeployResult> deployFromPackage(String siteKey, String runtimeType,
                                                    Map<String, Object> entrypoints,
                                                    Map<String, byte[]> entries) {
        if (!props.isEnabled()) {
            return Optional.empty();
        }
        String runtime = runtimeType == null ? "" : runtimeType.trim().toUpperCase(Locale.ROOT);
        if (!"REACT".equals(runtime) && !"VUE".equals(runtime)) {
            log.debug("webapps 部署跳过 siteKey={} runtime={}", siteKey, runtimeType);
            return Optional.empty();
        }
        String outputDir = outputDir(entrypoints);
        String outputPrefix = "template/" + outputDir + "/";
        Map<String, byte[]> prebuilt = filterPrefix(entries, outputPrefix);
        WebappsProperties.BuildMode mode = props.getBuild();

        Map<String, byte[]> output;
        String source;
        if (!prebuilt.isEmpty() && mode != WebappsProperties.BuildMode.ALWAYS) {
            output = prebuilt;
            source = "package-output";
        } else if (mode == WebappsProperties.BuildMode.NEVER) {
            log.warn("站点包无构建产物且 powercms.webapps.build=never，站点应用未部署 siteKey={}（期望 {}）",
                siteKey, outputPrefix);
            return Optional.empty();
        } else {
            output = build(siteKey, entrypoints, entries, outputDir);
            source = "build";
            if (output.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(deploy(siteKey, output, source));
    }

    /** 把产物写入 {@code webapps/{siteKey}/}（先清空旧目录再整树替换）。 */
    public DeployResult deploy(String siteKey, Map<String, byte[]> output, String source) {
        try {
            Path dir = siteDir(siteKey);
            if (!dir.startsWith(root())) {
                throw new SecurityException("站点应用目录越界: " + siteKey);
            }
            deleteTree(dir);
            Files.createDirectories(dir);
            long size = 0;
            int count = 0;
            for (var e : output.entrySet()) {
                Path file = safe(dir, e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue(), StandardOpenOption.CREATE_NEW);
                size += e.getValue().length;
                if (++count > MAX_OUTPUT_FILES) {
                    throw new IllegalStateException("站点应用产物文件数超限: " + MAX_OUTPUT_FILES);
                }
                if (size > MAX_OUTPUT_BYTES) {
                    throw new IllegalStateException("站点应用产物总大小超限: " + MAX_OUTPUT_BYTES);
                }
            }
            log.info("站点应用已部署 siteKey={} dir={} files={} bytes={} source={}",
                siteKey, dir, count, size, source);
            return new DeployResult(siteKey, siteKey, count, size, source);
        } catch (IOException e) {
            throw new UncheckedIOException("站点应用部署失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在站点应用目录内解析相对路径（防目录穿越）。
     *
     * @throws SecurityException 路径越界
     */
    public Path resolveSafe(Path siteDir, String relative) {
        return safe(siteDir, relative);
    }

    // ========== 现场构建（包内无产物时的 AUTO 回退） ==========

    private Map<String, byte[]> build(String siteKey, Map<String, Object> entrypoints,
                                      Map<String, byte[]> entries, String outputDir) {
        Map<String, byte[]> sources = filterPrefix(entries, "template/");
        if (sources.isEmpty()) {
            log.warn("站点包缺少 template/ 工程文件，无法构建站点应用 siteKey={}", siteKey);
            return Map.of();
        }
        Path workspace = null;
        String command = entrypoints.get("build") instanceof String s ? s.trim() : "";
        try {
            List<String> cmd = validatedCommand(command);
            workspace = Files.createTempDirectory("powercms-webapps-build-");
            for (var e : sources.entrySet()) {
                Path file = safe(workspace, e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue(), StandardOpenOption.CREATE_NEW);
            }
            if (!Files.isRegularFile(workspace.resolve("package.json"))) {
                log.warn("template/ 缺少 package.json，跳过站点应用构建 siteKey={}", siteKey);
                return Map.of();
            }
            if (Files.isRegularFile(workspace.resolve("package-lock.json"))) {
                run(workspace, List.of("npm", "ci"));
            } else {
                run(workspace, List.of("npm", "install"));
            }
            run(workspace, cmd);
            Path output = Files.isDirectory(workspace.resolve(outputDir))
                ? workspace.resolve(outputDir)
                : workspace.resolve("build");
            if (!Files.isDirectory(output)) {
                log.warn("构建未产出 {} 或 build 目录，站点应用未部署 siteKey={}", outputDir, siteKey);
                return Map.of();
            }
            return readTree(output);
        } catch (Exception e) {
            log.warn("站点应用构建失败 siteKey={} command={}：{}", siteKey, command, e.getMessage());
            return Map.of();
        } finally {
            if (workspace != null) {
                deleteTree(workspace);
            }
        }
    }

    /** 命令校验：白名单前缀 + 无 shell 元字符（参数数组方式执行，不经 shell）。 */
    private List<String> validatedCommand(String command) {
        if (command.isBlank()) {
            throw new IllegalStateException("entrypoints.build 为空");
        }
        if (command.matches(".*[;&|><$\\n\\r].*")) {
            throw new IllegalStateException("entrypoints.build 含非法元字符");
        }
        String lower = command.toLowerCase(Locale.ROOT);
        boolean allowed = pkgProps.getAllowedEntrypointPrefixes().stream()
            .anyMatch(p -> lower.startsWith(p.toLowerCase(Locale.ROOT)));
        if (!allowed) {
            throw new IllegalStateException("entrypoints.build 不在命令白名单内: " + command);
        }
        return List.of(command.split("\\s+"));
    }

    private void run(Path dir, List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        // 丢弃输出但持续读取，避免管道写满阻塞
        Thread drain = Thread.ofVirtual().start(() -> {
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            } catch (IOException ignored) {
                // 进程结束时流关闭，忽略
            }
        });
        long timeout = Math.max(30, props.getBuildTimeoutSeconds());
        if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("构建超时（" + timeout + "s）: " + String.join(" ", cmd));
        }
        drain.join(5_000);
        if (p.exitValue() != 0) {
            throw new IllegalStateException("构建命令失败（exit=" + p.exitValue() + "）: " + String.join(" ", cmd));
        }
    }

    // ========== 路径与文件工具 ==========

    /** 目录内安全解析：拒绝绝对路径、盘符/ADS、`..`、保留设备名，归一化后必须仍在根内。 */
    static Path safe(Path rootDir, String name) {
        if (name == null || name.isBlank()) {
            throw new SecurityException("空路径");
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*") || normalized.contains(":")) {
            throw new SecurityException("非法绝对路径/ADS: " + name);
        }
        for (String part : normalized.split("/")) {
            String stem = part.contains(".") ? part.substring(0, part.indexOf('.')) : part;
            if (part.equals("..") || stem.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw new SecurityException("非法路径段: " + name);
            }
        }
        Path p = rootDir.resolve(normalized).normalize();
        if (!p.startsWith(rootDir)) {
            throw new SecurityException("路径穿越: " + name);
        }
        return p;
    }

    private static Map<String, byte[]> filterPrefix(Map<String, byte[]> entries, String prefix) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (var e : entries.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getKey().length() > prefix.length()) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    private static Map<String, byte[]> readTree(Path dir) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return out;
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("清理目录失败: " + dir, e);
        }
    }

    private static String outputDir(Map<String, Object> entrypoints) {
        Object v = entrypoints == null ? null : entrypoints.get("output");
        String dir = v == null ? "dist" : String.valueOf(v).trim();
        return dir.isBlank() ? "dist" : dir;
    }
}
