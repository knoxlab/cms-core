package com.powercms.cms.search.web;

import com.powercms.cms.search.IndexSyncGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 索引状态查询（FR-SEARCH-007）：展示索引落后量对应的 releaseId 与同步状态。
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/search")
public class IndexStatusController {

    private final ObjectProvider<IndexSyncGate> gate;

    public IndexStatusController(ObjectProvider<IndexSyncGate> gate) {
        this.gate = gate;
    }

    @org.springframework.security.access.prepost.PreAuthorize(
        "@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/status")
    public IndexSyncGate.IndexStatus status(@PathVariable UUID siteId) {
        var indexGate = gate.getIfAvailable();
        if (indexGate == null) {
            throw new com.powercms.common.error.BusinessException(
                com.powercms.common.error.ErrorCodes.NOT_FOUND, "索引门禁未启用");
        }
        return indexGate.status(siteId);
    }
}
