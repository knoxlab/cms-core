package com.powercms.cms.publish;

import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitPublisherTest {

    // FR-PUBLISH-003：commit message 必须包含 siteId、releaseId 与任务 ID
    @Test
    void commitMessageContainsSiteReleaseAndTaskIds() {
        var site = UUID.randomUUID();
        var release = UUID.randomUUID();
        var task = UUID.randomUUID();

        var msg = GitPublisher.commitMessage(site, release, task);

        assertThat(msg).contains("site=" + site, "release=" + release, "task=" + task);
        assertThat(msg).startsWith("PowerCMS release");
    }

    // FR-PUBLISH-006：历史产物缺失时回滚必须被拒绝（不触发 git 进程）
    @Test
    void rollbackRejectsMissingArtifactDirectory() throws Exception {
        var target = new com.powercms.cms.publish.entity.DeployTargetEntity();
        target.setId(UUID.randomUUID());
        target.setTargetType("GIT");
        target.setConfig("{\"repositoryUrl\":\"https://example.com/repo.git\",\"branch\":\"prod\"}");
        var missing = java.nio.file.Path.of(Files.createTempDirectory("no-such-art").toAbsolutePath() + "/gone");

        assertThatThrownBy(() -> new GitPublisher(new com.fasterxml.jackson.databind.ObjectMapper(), "target")
                .rollback(target, UUID.randomUUID(), UUID.randomUUID(), missing, UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("历史产物");
    }
}
