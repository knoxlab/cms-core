package com.powercms.cms.audit.web;

import com.powercms.cms.audit.AccessDenialQuery;
import com.powercms.cms.audit.repo.AuditEventRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 站点访问拒绝审计。只返回页面、播放、下载被拒事件。 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/access-audit")
public class AccessAuditController {

    private final AuditEventRepository auditRepo;
    private final AccessDenialQuery query;

    public AccessAuditController(AuditEventRepository auditRepo, AccessDenialQuery query) {
        this.auditRepo = auditRepo;
        this.query = query;
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping
    public AccessDenialQuery.AuditPage list(@PathVariable UUID siteId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String action) {
        return query.query(
            pageable -> auditRepo.findBySiteIdOrderByOccurredAtDesc(siteId, pageable),
            action, page, size);
    }
}
