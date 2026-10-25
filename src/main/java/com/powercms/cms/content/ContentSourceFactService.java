package com.powercms.cms.content;

import com.powercms.cms.content.entity.ContentSourceFactEntity;
import com.powercms.cms.content.repo.ContentSourceFactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * FR-AI-SRC-004 统一来源事实元数据服务：外部来源（DAM/PIM/第三方）同步投影时写入，
 * 引用闸门（{@code AiTrustGuard}）消费其来源审批状态与有效期。
 *
 * <p>单一裁决口径：内容是否可被 AI 引用由「内容可信等级 + 内容审批状态 + 来源事实
 * 审批状态/有效期」共同决定，全部集中在 {@link #rejectionReason} 中，RAG 问答与图谱遍历共用。
 */
@Service
public class ContentSourceFactService {

    private final ContentSourceFactRepository repo;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ContentSourceFactService(ContentSourceFactRepository repo) {
        this(repo, Clock.systemUTC());
    }

    public ContentSourceFactService(ContentSourceFactRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 幂等 upsert：同一 content_item 只保留一条来源事实。 */
    @Transactional
    public ContentSourceFactEntity upsert(UUID contentItemId, UUID siteId, String sourceSystem,
                                          String sourceObjectId, String sourceVersion,
                                          String approvalStatus, Instant validFrom, Instant validUntil,
                                          Integer trustLevel, String copyright, String syncCursor) {
        ContentSourceFactEntity fact = repo.findById(contentItemId)
            .orElseGet(ContentSourceFactEntity::new);
        Instant now = clock.instant();
        fact.setContentItemId(contentItemId);
        fact.setSiteId(siteId);
        fact.setSourceSystem(sourceSystem);
        fact.setSourceObjectId(sourceObjectId);
        fact.setSourceVersion(sourceVersion);
        fact.setApprovalStatus(approvalStatus);
        fact.setValidFrom(validFrom);
        fact.setValidUntil(validUntil);
        if (trustLevel != null) {
            fact.setTrustLevel(trustLevel);
        }
        fact.setCopyright(copyright);
        fact.setSyncCursor(syncCursor);
        fact.setSyncedAt(now);
        if (fact.getUpdatedAt() == null) {
            fact.setUpdatedAt(now);
        }
        return repo.save(fact);
    }

    public Optional<ContentSourceFactEntity> get(UUID contentItemId) {
        return contentItemId == null ? Optional.empty() : repo.findById(contentItemId);
    }

    /**
     * FR-AI-SRC-001 同步幂等锚点：按来源系统 + 外部对象 ID 反查已登记的内容条目（最早登记在前）。
     *
     * <p>外部唯一键的权威落点就是本表 {@code source_object_id}：外部键无需再用一条字段映射
     * 落到内容模型里，同步即可凭锚点定位既有条目。任一路径为空时返回空列表。
     */
    public List<ContentSourceFactEntity> findBySourceKey(UUID siteId, String sourceSystem,
                                                         String sourceObjectId) {
        if (siteId == null || sourceSystem == null || sourceSystem.isBlank()
            || sourceObjectId == null || sourceObjectId.isBlank()) {
            return List.of();
        }
        return repo.findBySourceKey(siteId, sourceSystem, sourceObjectId);
    }

    public List<ContentSourceFactEntity> findBySiteId(UUID siteId) {
        return repo.findBySiteId(siteId);
    }

    /** 批量取来源事实（避免逐条查询）。 */
    public Map<UUID, ContentSourceFactEntity> factsOf(Collection<UUID> contentItemIds) {
        Map<UUID, ContentSourceFactEntity> out = new LinkedHashMap<>();
        if (contentItemIds == null || contentItemIds.isEmpty()) {
            return out;
        }
        for (ContentSourceFactEntity fact : repo.findByContentItemIdIn(contentItemIds)) {
            out.put(fact.getContentItemId(), fact);
        }
        return out;
    }

    /**
     * 来源侧引用拒绝原因；{@code null} 表示放行。
     *
     * <p>判定顺序：来源审批状态（PENDING/REJECTED → {@code source_not_approved}）→
     * 有效期（未生效 → {@code source_not_yet_valid}；已过期 → {@code source_expired}）→
     * 来源可信等级低于站点下限（{@code trust_below_min}）。
     */
    public String rejectionReason(ContentSourceFactEntity fact, int minTrust) {
        if (fact == null) {
            return null;
        }
        String approval = fact.getApprovalStatus();
        if (ContentSourceFactEntity.APPROVAL_PENDING.equals(approval)
            || ContentSourceFactEntity.APPROVAL_REJECTED.equals(approval)) {
            return "source_not_approved";
        }
        Instant now = clock.instant();
        if (fact.getValidFrom() != null && now.isBefore(fact.getValidFrom())) {
            return "source_not_yet_valid";
        }
        if (fact.getValidUntil() != null && now.isAfter(fact.getValidUntil())) {
            return "source_expired";
        }
        if (fact.getTrustLevel() < minTrust) {
            return "trust_below_min";
        }
        return null;
    }
}
