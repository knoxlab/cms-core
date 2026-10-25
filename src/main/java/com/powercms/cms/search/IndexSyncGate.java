package com.powercms.cms.search;

import com.powercms.cms.publish.ReleaseDeployRequestedEvent;
import com.powercms.cms.publish.ReleaseService;
import com.powercms.cms.search.entity.SiteIndexStatusEntity;
import com.powercms.cms.search.repo.SiteIndexStatusRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * 索引落后门禁（FR-SEARCH-007 / FR-SITE-ENV-006）：
 * 部署事务提交后校验访客索引 releaseId 与目标 Release 一致；不一致时生产发布回滚为 FROZEN 并阻断。
 * rebuild 落库记录最近索引的 releaseId，供门禁与状态接口读取。
 *
 * <p><b>口径统一（FR-SEARCH-002 + FR-SEARCH-007）</b>：门禁此前只做**校验**——一旦索引的
 * {@code last_release_id} 与目标 Release 不同就直接回退部署。而访客索引的唯一合法人群是
 * 「已部署 Release 成员」，即 {@link PublicSearchService#rebuild(UUID)} 只能按**已部署** Release 建：
 * **首次部署时**索引必然还没登记该 Release（部署前它还不是 DEPLOYED），于是门禁必然回退 ——
 * 鸡生蛋，严格 Release 口径下永远无法完成首次部署。
 *
 * <p>现改为**先自愈再校验**（对齐规格发布时序「建立并验证候选访客索引 → 切换 → 门禁」）：
 * 不一致时按本次部署的 **target Release** 建立并切换候选访客索引（
 * {@link PublicSearchService#rebuildForRelease(UUID, UUID)}），成功即放行；**建不出来或建完仍不一致**
 * 才按门禁回退（{@code undeploy} + {@link ErrorCodes#INDEX_RELEASE_MISMATCH}），
 * 保持「索引与 Release 不一致时生产发布不得完成」这一硬约束。
 */
@Component
public class IndexSyncGate {

    private static final Logger log = LoggerFactory.getLogger(IndexSyncGate.class);

    private final SiteIndexStatusRepository statusRepo;
    private final ReleaseService releaseService;
    private final PublicSearchService publicSearchService;
    private final boolean enforceProduction;

    public IndexSyncGate(SiteIndexStatusRepository statusRepo,
                         ReleaseService releaseService,
                         PublicSearchService publicSearchService,
                         @Value("${powercms.search.index-gate-enabled:true}") boolean enforceProduction) {
        this.statusRepo = statusRepo;
        this.releaseService = releaseService;
        this.publicSearchService = publicSearchService;
        this.enforceProduction = enforceProduction;
    }

    /** PublicSearchService.rebuild 成功后调用：登记站点索引当前对应的 releaseId。 */
    public void recordRebuild(UUID siteId, String indexAlias, UUID releaseId, long documentCount) {
        var existing = statusRepo.findById(siteId).orElse(null);
        if (existing == null) {
            statusRepo.save(SiteIndexStatusEntity.of(siteId, indexAlias, releaseId, documentCount));
        } else {
            existing.setIndexAlias(indexAlias);
            existing.setLastReleaseId(releaseId);
            existing.setDocumentCount(documentCount);
            existing.setRebuiltAt(java.time.Instant.now());
            statusRepo.save(existing);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeployRequested(ReleaseDeployRequestedEvent event) {
        if (!enforceProduction) return;
        if (inSync(event.siteId(), event.releaseId())) return;
        // 自愈：按**本次部署的 Release** 建立并切换候选访客索引（首次部署的唯一可用时机）
        try {
            log.info("index lag gate: rebuilding public index for release={} site={}",
                event.releaseId(), event.siteId());
            publicSearchService.rebuildForRelease(event.siteId(), event.releaseId());
        } catch (RuntimeException ex) {
            log.error("index lag gate: self-heal failed for release={} site={}: {}",
                event.releaseId(), event.siteId(), ex.getMessage());
            blockDeploy(event, "访客索引自愈失败：" + ex.getMessage());
            return;
        }
        if (!inSync(event.siteId(), event.releaseId())) {
            blockDeploy(event, "访客索引自愈后仍与目标 Release 不一致（未登记 last_release_id）");
        }
    }

    /** 门禁回退：撤销本次部署并抛出契约错误，保持「索引不一致 → 生产发布不得完成」。 */
    private void blockDeploy(ReleaseDeployRequestedEvent event, String reason) {
        var status = statusRepo.findById(event.siteId()).orElse(null);
        log.warn("index lag gate blocked deploy: site={} release={} indexed={} reason={}",
            event.siteId(), event.releaseId(), status == null ? null : status.getLastReleaseId(), reason);
        try {
            releaseService.undeploy(event.releaseId());
        } catch (Exception ex) {
            log.error("failed to revert deployment {} after gate failure: {}", event.releaseId(), ex.getMessage(), ex);
        }
        throw new BusinessException(ErrorCodes.INDEX_RELEASE_MISMATCH,
                "访客索引落后于待发布 Release，生产发布被门禁阻断；请先重建访客索引（" + reason + "）");
    }

    private boolean inSync(UUID siteId, UUID releaseId) {
        var status = statusRepo.findById(siteId).orElse(null);
        return status != null && releaseId != null && releaseId.equals(status.getLastReleaseId());
    }

    public record IndexStatus(UUID siteId, String indexAlias, UUID lastReleaseId,
                              long documentCount, java.time.Instant rebuiltAt, boolean inSync) {}

    public IndexStatus status(UUID siteId) {
        var deployed = releaseService.getDeployed(siteId);
        var status = statusRepo.findById(siteId).orElse(null);
        UUID deployedReleaseId = deployed == null ? null : deployed.id();
        return new IndexStatus(siteId,
            status == null ? null : status.getIndexAlias(),
            status == null ? null : status.getLastReleaseId(),
            status == null ? 0 : status.getDocumentCount(),
            status == null ? null : status.getRebuiltAt(),
            status != null && deployedReleaseId != null && deployedReleaseId.equals(status.getLastReleaseId()));
    }
}
