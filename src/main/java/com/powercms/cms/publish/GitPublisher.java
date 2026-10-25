package com.powercms.cms.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.publish.entity.DeployTargetEntity;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Git 发布目标：专用分支、机器身份（依赖运行环境凭据）、可追踪提交。
 * FR-PUBLISH-003：commit message 必须含 siteId、releaseId 与任务 ID；推送冲突走失败，绝不强推。
 */
@Component
public class GitPublisher {

    private final ObjectMapper mapper;
    private final Path root;

    GitPublisher(ObjectMapper mapper,
                 @Value("${powercms.deploy.git-work-root:${java.io.tmpdir}/powercms-git-deploy}") String root) {
        this.mapper = mapper;
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    public void publish(DeployTargetEntity target, UUID releaseId, Path artifact) {
        publish(target, null, releaseId, artifact, null);
    }

    /** 将发布产物提交并推送到目标分支。siteId/taskId 参与 commit message（FR-PUBLISH-003）。 */
    public void publish(DeployTargetEntity target, UUID siteId, UUID releaseId, Path artifact, UUID taskId) {
        Path work = null;
        try {
            JsonNode c = mapper.readTree(target.getConfig());
            String url = c.path("repositoryUrl").asText("");
            String branch = c.path("branch").asText("main");
            validateUrl(url);
            if (!branch.matches("[A-Za-z0-9._/-]+") || branch.contains("..")) {
                throw new IllegalArgumentException("invalid branch");
            }
            if (artifact == null || !Files.isDirectory(artifact)) {
                throw new IllegalArgumentException("发布产物目录不存在: " + artifact);
            }
            work = Files.createDirectories(root.resolve(target.getId() + "-" + UUID.randomUUID()));
            run(work, List.of("git", "clone", "--depth", "1", "--branch", branch, "--", url, "repo"));
            Path repo = work.resolve("repo");
            clear(repo);
            copy(artifact, repo);
            run(repo, List.of("git", "add", "-A"));
            if (!changed(repo)) {
                return;
            }
            run(repo, List.of("git", "commit", "-m", commitMessage(siteId, releaseId, taskId)));
            run(repo, List.of("git", "push", "origin", "HEAD:" + branch));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "Git publish failed: " + safe(ex.getMessage()));
        } finally {
            if (work != null) {
                delete(work);
            }
        }
    }

    /**
     * Git 目标回滚：以历史发布产物重放一次可追踪提交（不改写历史、不强推），
     * 使分支内容回到指定历史 Release。产物缺失时拒绝（FR-PUBLISH-006）。
     */
    public void rollback(DeployTargetEntity target, UUID siteId, UUID releaseId, Path artifact, UUID taskId) {
        if (artifact == null || !Files.isDirectory(artifact)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "历史产物目录缺失，无法回滚: " + artifact);
        }
        publish(target, siteId, releaseId, artifact, taskId);
    }

    /** FR-PUBLISH-003：commit message 含 siteId、releaseId 与任务 ID。 */
    static String commitMessage(UUID siteId, UUID releaseId, UUID taskId) {
        return "PowerCMS release site=" + siteId + " release=" + releaseId + " task=" + taskId;
    }

    private void validateUrl(String raw) {
        if (raw.isBlank()) {
            throw new IllegalArgumentException("repositoryUrl is required");
        }
        if (raw.contains("\n") || raw.contains("\r")) {
            throw new IllegalArgumentException("invalid repositoryUrl");
        }
        if (raw.contains("://")) {
            URI u = URI.create(raw);
            if (!("https".equalsIgnoreCase(u.getScheme()) || "ssh".equalsIgnoreCase(u.getScheme()))
                    || u.getUserInfo() != null) {
                throw new IllegalArgumentException("Git URL must use HTTPS/SSH without embedded credentials");
            }
        } else if (!raw.matches("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("unsupported Git URL");
        }
    }

    private void run(Path dir, List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git timeout");
        }
        if (p.exitValue() != 0) {
            throw new IOException("git command failed: " + redact(out));
        }
    }

    private boolean changed(Path repo) throws Exception {
        Process p = new ProcessBuilder("git", "status", "--porcelain").directory(repo.toFile()).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return !out.isBlank();
    }

    private void clear(Path repo) throws IOException {
        try (var s = Files.list(repo)) {
            s.filter(p -> !p.getFileName().toString().equals(".git")).forEach(this::delete);
        }
    }

    private void copy(Path a, Path b) throws IOException {
        try (var s = Files.walk(a)) {
            s.forEach(p -> {
                try {
                    Path q = b.resolve(a.relativize(p));
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(q);
                    } else {
                        Files.copy(p, q, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void delete(Path p) {
        try {
            if (!Files.exists(p)) {
                return;
            }
            try (var s = Files.walk(p).sorted(Comparator.reverseOrder())) {
                s.forEach(x -> {
                    try {
                        Files.deleteIfExists(x);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    private String safe(String s) {
        if (s == null) {
            return "unknown error";
        }
        String r = redact(s);
        return r.substring(0, Math.min(500, r.length()));
    }

    private String redact(String s) {
        return s.replaceAll("(?i)(token|password|authorization)[=: ]+[^\\s]+", "$1=[REDACTED]");
    }
}
