package com.powercms.cms.publish;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.publish.entity.BuildArtifactEntity;
import com.powercms.cms.publish.entity.DeployTargetEntity;
import com.powercms.cms.publish.entity.DeploymentEntity;
import com.powercms.cms.publish.entity.ReleaseEntity;
import com.powercms.cms.publish.repo.BuildArtifactRepository;
import com.powercms.cms.publish.repo.BuildJobRepository;
import com.powercms.cms.publish.repo.DeployTargetRepository;
import com.powercms.cms.publish.repo.DeploymentRepository;
import com.powercms.cms.publish.repo.ReleaseRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeployServiceTest {

    @TempDir
    Path temp;

    private final DeployTargetRepository targetRepo = mock(DeployTargetRepository.class);
    private final BuildArtifactRepository artifactRepo = mock(BuildArtifactRepository.class);
    private final DeploymentRepository deploymentRepo = mock(DeploymentRepository.class);
    private final BuildJobRepository buildJobRepo = mock(BuildJobRepository.class);
    private final ReleaseRepository releaseRepo = mock(ReleaseRepository.class);
    private final LocalNginxPublisher publisher = mock(LocalNginxPublisher.class);
    private final GitPublisher gitPublisher = mock(GitPublisher.class);
    private final DeploymentHealthChecker healthChecker = mock(DeploymentHealthChecker.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AuditService audit = mock(AuditService.class);

    private DeployService service() {
        return new DeployService(targetRepo, artifactRepo, deploymentRepo, buildJobRepo,
            releaseRepo, publisher, gitPublisher, healthChecker, events, audit, mock(StaticPublishGuard.class));
    }

    private DeployTargetEntity target(UUID id, UUID siteId, String type, String config) {
        var t = new DeployTargetEntity();
        t.setId(id);
        t.setSiteId(siteId);
        t.setName("t-" + type);
        t.setTargetType(type);
        t.setConfig(config);
        return t;
    }

    private ReleaseEntity release(UUID id, UUID siteId, String status) {
        var r = new ReleaseEntity();
        r.setId(id);
        r.setSiteId(siteId);
        r.setStatus(status);
        return r;
    }

    private BuildArtifactEntity artifact(UUID id, UUID releaseId, Path dir) {
        var a = new BuildArtifactEntity();
        a.setId(id);
        a.setReleaseId(releaseId);
        a.setArtifactPath(dir.toString());
        a.setBuildType(BuildArtifactEntity.BUILD_FULL);
        return a;
    }

    private DeploymentEntity deployment(UUID id, UUID siteId, UUID releaseId, UUID targetId,
                                         UUID artifactId, String status) {
        var d = new DeploymentEntity();
        d.setId(id);
        d.setSiteId(siteId);
        d.setReleaseId(releaseId);
        d.setDeployTargetId(targetId);
        d.setArtifactId(artifactId);
        d.setBuildType("FULL");
        d.setStatus(status);
        d.setDeployedBy("u1");
        return d;
    }

    private void stubSaveReturnsArgument() {
        when(deploymentRepo.save(any())).thenAnswer(i -> {
            DeploymentEntity e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
    }

    // ---------- FR-PUBLISH-001: 服务端强制 FROZEN ----------

    @Test
    void createDeploymentRejectsNonFrozenRelease() {
        var site = UUID.randomUUID();
        var relId = UUID.randomUUID();
        var t = target(UUID.randomUUID(), site, DeployTargetEntity.TYPE_LOCAL_NGINX, "{}");
        var rel = release(relId, site, ReleaseEntity.STATUS_DRAFT);
        var art = artifact(UUID.randomUUID(), relId, temp);
        when(targetRepo.findById(t.getId())).thenReturn(Optional.of(t));
        when(releaseRepo.findById(relId)).thenReturn(Optional.of(rel));
        when(artifactRepo.findById(art.getId())).thenReturn(Optional.of(art));

        assertThatThrownBy(() -> service().createDeployment(site, relId, t.getId(), art.getId(), "FULL", null, "u1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("冻结");
    }

    // ---------- FR-PUBLISH-004: 同目标串行化 ----------

    @Test
    void createDeploymentRejectsWhenActiveDeploymentExistsOnTarget() {
        var site = UUID.randomUUID();
        var relId = UUID.randomUUID();
        var t = target(UUID.randomUUID(), site, DeployTargetEntity.TYPE_LOCAL_NGINX, "{}");
        var rel = release(relId, site, ReleaseEntity.STATUS_FROZEN);
        var art = artifact(UUID.randomUUID(), relId, temp);
        when(targetRepo.findById(t.getId())).thenReturn(Optional.of(t));
        when(releaseRepo.findById(relId)).thenReturn(Optional.of(rel));
        when(artifactRepo.findById(art.getId())).thenReturn(Optional.of(art));
        when(deploymentRepo.existsByDeployTargetIdAndStatusIn(eq(t.getId()), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service().createDeployment(site, relId, t.getId(), art.getId(), "FULL", null, "u1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("串行");
        verify(deploymentRepo, never()).save(any());
    }

    // ---------- FR-PUBLISH-004: 排期未到不可提前发布 ----------

    @Test
    void publishRejectsScheduledDeploymentBeforeDue() {
        var site = UUID.randomUUID();
        var d = deployment(UUID.randomUUID(), site, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            DeploymentEntity.STATUS_QUEUED);
        d.setScheduledAt(Instant.now().plus(2, ChronoUnit.HOURS));
        when(deploymentRepo.findById(d.getId())).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> service().publish(d.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未到排期发布时间");
    }

    // ---------- FR-PUBLISH-007: 发布前健康检查不健康则终止 ----------

    @Test
    void publishBlocksWhenPreHealthCheckUnhealthy() throws Exception {
        stubSaveReturnsArgument();
        var site = UUID.randomUUID();
        var t = target(UUID.randomUUID(), site, DeployTargetEntity.TYPE_LOCAL_NGINX, "{}");
        var artDir = Files.createDirectory(temp.resolve("art0"));
        var art = artifact(UUID.randomUUID(), UUID.randomUUID(), artDir);
        var d = deployment(UUID.randomUUID(), site, UUID.randomUUID(), t.getId(), art.getId(), DeploymentEntity.STATUS_QUEUED);
        when(deploymentRepo.findById(d.getId())).thenReturn(Optional.of(d));
        when(targetRepo.findById(t.getId())).thenReturn(Optional.of(t));
        when(artifactRepo.findById(art.getId())).thenReturn(Optional.of(art));
        when(healthChecker.check(t)).thenReturn(new DeploymentHealthChecker.Result(true, false, "HTTP 503 expected 200"));

        var result = service().publish(d.getId());

        assertThat(result.status()).isEqualTo(DeploymentEntity.STATUS_FAILED);
        assertThat(result.healthCheckBefore()).isEqualTo(DeploymentEntity.HEALTH_UNHEALTHY);
        assertThat(result.note()).contains("发布前健康检查失败");
        verify(publisher, never()).publish(any(), any(), any());
        verify(audit).write(eq("u1"), eq("DEPLOYMENT_PUBLISH"), eq("deployment"), eq(d.getId().toString()), eq(site), eq("FAILED"), isNull(), anyString());
    }

    // ---------- FR-PUBLISH-002/005: 本地发布成功并同步 Release 状态 ----------

    @Test
    void publishSuccessMarksCurrentReleaseDeployedAndDemotesPrevious() throws Exception {
        stubSaveReturnsArgument();
        var site = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var relId = UUID.randomUUID();
        var oldRelId = UUID.randomUUID();
        var artDir = Files.createDirectory(temp.resolve("art1"));
        var t = target(targetId, site, DeployTargetEntity.TYPE_LOCAL_NGINX, "{}");
        var rel = release(relId, site, ReleaseEntity.STATUS_FROZEN);
        var oldRel = release(oldRelId, site, ReleaseEntity.STATUS_DEPLOYED);
        var art = artifact(UUID.randomUUID(), relId, artDir);
        var d = deployment(UUID.randomUUID(), site, relId, targetId, art.getId(), DeploymentEntity.STATUS_QUEUED);

        when(deploymentRepo.findById(d.getId())).thenReturn(Optional.of(d));
        when(targetRepo.findById(targetId)).thenReturn(Optional.of(t));
        when(artifactRepo.findById(art.getId())).thenReturn(Optional.of(art));
        when(releaseRepo.findById(relId)).thenReturn(Optional.of(rel));
        when(releaseRepo.findBySiteIdAndStatus(site, ReleaseEntity.STATUS_DEPLOYED)).thenReturn(Optional.of(oldRel));
        when(healthChecker.check(t)).thenReturn(new DeploymentHealthChecker.Result(false, true, "not configured"));

        var result = service().publish(d.getId());

        assertThat(result.status()).isEqualTo(DeploymentEntity.STATUS_DEPLOYED);
        ArgumentCaptor<ReleaseEntity> saves = ArgumentCaptor.forClass(ReleaseEntity.class);
        verify(releaseRepo, org.mockito.Mockito.atLeastOnce()).save(saves.capture());
        assertThat(saves.getAllValues()).extracting(ReleaseEntity::getStatus)
            .contains(ReleaseEntity.STATUS_DEPLOYED, ReleaseEntity.STATUS_ROLLED_BACK);
        verify(publisher).publish(eq(t), eq(relId), any());
        verify(audit).write(eq("u1"), eq("DEPLOYMENT_PUBLISH"), eq("deployment"), eq(d.getId().toString()), eq(site), eq("SUCCESS"), isNull(), anyString());
    }

    // ---------- FR-PUBLISH-006: Git 目标回滚走 GitPublisher 并同步状态 ----------

    @Test
    void rollbackOnGitTargetPushesHistoricalArtifactAndSyncsReleaseState() throws Exception {
        stubSaveReturnsArgument();
        var site = UUID.randomUUID();
        var curRel = UUID.randomUUID();
        var prevRel = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var prevArtDir = Files.createDirectory(temp.resolve("prevArt"));
        var t = target(targetId, site, DeployTargetEntity.TYPE_GIT,
            "{\"repositoryUrl\":\"https://example.com/repo.git\",\"branch\":\"prod\"}");
        var prevArt = artifact(UUID.randomUUID(), prevRel, prevArtDir);
        var cur = deployment(UUID.randomUUID(), site, curRel, targetId, UUID.randomUUID(),
            DeploymentEntity.STATUS_DEPLOYED);
        var prev = deployment(UUID.randomUUID(), site, prevRel, targetId, prevArt.getId(),
            DeploymentEntity.STATUS_DEPLOYED);
        cur.setPreviousDeploymentId(prev.getId());
        var curRelEnt = release(curRel, site, ReleaseEntity.STATUS_DEPLOYED);
        var prevRelEnt = release(prevRel, site, ReleaseEntity.STATUS_ROLLED_BACK);

        when(deploymentRepo.findById(cur.getId())).thenReturn(Optional.of(cur));
        when(deploymentRepo.findById(prev.getId())).thenReturn(Optional.of(prev));
        when(targetRepo.findById(targetId)).thenReturn(Optional.of(t));
        when(artifactRepo.findById(prevArt.getId())).thenReturn(Optional.of(prevArt));
        when(healthChecker.check(t)).thenReturn(new DeploymentHealthChecker.Result(false, true, "not configured"));
        when(releaseRepo.findById(curRel)).thenReturn(Optional.of(curRelEnt));
        when(releaseRepo.findById(prevRel)).thenReturn(Optional.of(prevRelEnt));

        var result = service().rollback(cur.getId());

        assertThat(result.status()).isEqualTo(DeploymentEntity.STATUS_DEPLOYED);
        assertThat(result.releaseId()).isEqualTo(prevRel);
        assertThat(result.note()).contains("回滚自");
        verify(gitPublisher).rollback(eq(t), eq(site), eq(prevRel), any(), eq(cur.getId()));
        verify(audit).write(eq("u1"), eq("DEPLOYMENT_ROLLBACK"), eq("deployment"), eq(cur.getId().toString()), eq(site), eq("SUCCESS"), isNull(), anyString());
        ArgumentCaptor<ReleaseEntity> saves = ArgumentCaptor.forClass(ReleaseEntity.class);
        verify(releaseRepo, org.mockito.Mockito.atLeastOnce()).save(saves.capture());
        assertThat(saves.getAllValues()).extracting(ReleaseEntity::getStatus)
            .contains(ReleaseEntity.STATUS_DEPLOYED, ReleaseEntity.STATUS_ROLLED_BACK);
    }

    // ---------- FR-PUBLISH-006: 回滚前置条件 ----------

    @Test
    void rollbackRequiresDeployedStatus() {
        var d = deployment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), DeploymentEntity.STATUS_QUEUED);
        when(deploymentRepo.findById(d.getId())).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> service().rollback(d.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅已部署的记录可回滚");
    }

    @Test
    void rollbackFailsWhenNoPreviousDeployment() {
        var d = deployment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), DeploymentEntity.STATUS_DEPLOYED);
        when(deploymentRepo.findById(d.getId())).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> service().rollback(d.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无可回滚的历史部署");
    }

    // ---------- FR-PUBLISH-004/005: 创建成功与审计 ----------

    @Test
    void createDeploymentSucceedsForFrozenReleaseWithScheduledAtAndAudits() {
        stubSaveReturnsArgument();
        var site = UUID.randomUUID();
        var relId = UUID.randomUUID();
        var t = target(UUID.randomUUID(), site, DeployTargetEntity.TYPE_LOCAL_NGINX, "{}");
        var rel = release(relId, site, ReleaseEntity.STATUS_FROZEN);
        var art = artifact(UUID.randomUUID(), relId, temp);
        when(targetRepo.findById(t.getId())).thenReturn(Optional.of(t));
        when(releaseRepo.findById(relId)).thenReturn(Optional.of(rel));
        when(artifactRepo.findById(art.getId())).thenReturn(Optional.of(art));
        when(deploymentRepo.existsByDeployTargetIdAndStatusIn(eq(t.getId()), anyCollection())).thenReturn(false);
        when(deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(t.getId(), DeploymentEntity.STATUS_DEPLOYED))
            .thenReturn(Optional.empty());

        var scheduled = Instant.now().plus(1, ChronoUnit.HOURS);
        var created = service().createDeployment(site, relId, t.getId(), art.getId(), "FULL", scheduled, "u1");

        assertThat(created.status()).isEqualTo(DeploymentEntity.STATUS_QUEUED);
        assertThat(created.scheduledAt()).isEqualTo(scheduled);
        verify(audit).write(eq("u1"), eq("DEPLOYMENT_CREATE"), eq("deployment"), eq(created.id().toString()), eq(site), eq("SUCCESS"), isNull(), anyString());
    }
}
