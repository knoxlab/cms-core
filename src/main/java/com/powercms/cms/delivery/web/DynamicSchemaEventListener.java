package com.powercms.cms.delivery.web;

import com.powercms.cms.delivery.DynamicSchemaRegistry;
import com.powercms.cms.model.ContentTypePublishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 模型发布 → 重建站点 GraphQL schema（FR-API-003）。
 * 候选构建失败仅记录告警，不影响现有 schema（模型发布失败不得替换现有 schema）。
 */
@Component
public class DynamicSchemaEventListener {

    private static final Logger log = LoggerFactory.getLogger(DynamicSchemaEventListener.class);

    private final DynamicSchemaRegistry registry;

    public DynamicSchemaEventListener(DynamicSchemaRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModelPublished(ContentTypePublishedEvent event) {
        if (event == null || event.siteId() == null) return;
        try {
            registry.rebuildForSite(event.siteId());
        } catch (Exception ex) {
            log.warn("GraphQL schema rebuild failed for site {}: {}",
                event.siteId(), ex.getMessage());
        }
    }
}
