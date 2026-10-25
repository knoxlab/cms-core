package com.powercms.cms.model;

import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.repo.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 存量数据修复（FR-CM-CT-014 扩展）：发布即废弃规则上线前，对同一内容模型创建新版本
 * 并发布会导致同 site+code 出现多行 PUBLISHED——内容创建双入口且 GraphQL schema
 * 重复定义而重建失败。启动时按版本号保留最高版本，其余行统一置为 DEPRECATED（幂等）。
 */
@Component
public class ContentTypePublishRepairJob {

    private static final Logger log = LoggerFactory.getLogger(ContentTypePublishRepairJob.class);

    private final ContentTypeRepository typeRepo;
    private final SiteRepository siteRepo;

    public ContentTypePublishRepairJob(ContentTypeRepository typeRepo, SiteRepository siteRepo) {
        this.typeRepo = typeRepo;
        this.siteRepo = siteRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void repair() {
        for (UUID siteId : siteRepo.findAll().stream().map(s -> s.getId()).toList()) {
            try {
                repairSite(siteId);
            } catch (Exception ex) {
                log.warn("content type publish repair failed site={} err={}", siteId, ex.getMessage());
            }
        }
    }

    void repairSite(UUID siteId) {
        Map<String, List<ContentTypeEntity>> publishedByCode = new TreeMap<>();
        for (ContentTypeEntity ct : typeRepo.findBySiteIdOrderByCodeAsc(siteId)) {
            if (ContentTypeEntity.STATUS_PUBLISHED.equals(ct.getStatus())) {
                publishedByCode.computeIfAbsent(ct.getCode(), k -> new java.util.ArrayList<>()).add(ct);
            }
        }
        for (Map.Entry<String, List<ContentTypeEntity>> entry : publishedByCode.entrySet()) {
            List<ContentTypeEntity> rows = entry.getValue();
            if (rows.size() <= 1) continue;
            rows.sort(Comparator.comparingInt(ContentTypeEntity::getVersion));
            rows.subList(0, rows.size() - 1).forEach(stale -> {
                stale.setStatus(ContentTypeEntity.STATUS_DEPRECATED);
                typeRepo.save(stale);
                log.info("content type deprecated by startup repair: site={} code={} version={}",
                    siteId, stale.getCode(), stale.getVersion());
            });
        }
    }
}
