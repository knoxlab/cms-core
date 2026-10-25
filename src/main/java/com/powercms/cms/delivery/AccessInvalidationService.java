package com.powercms.cms.delivery;

import com.powercms.cms.identity.permission.AccessControlChangedEvent;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.identity.permission.ProgramAccessService;
import com.powercms.cms.publish.AffectedPageResolver;
import com.powercms.cms.publish.SsgPublishCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 权限或策略变更后的失效链（FR-ACCESS-012）：清渲染缓存、通知 CDN、不再公开的对象触发重新发布。
 * 放在交付模块，避免身份模块反向依赖交付和发布。
 */
@Service
public class AccessInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(AccessInvalidationService.class);

    private final RenderDatasetCache datasetCache;
    private final CdnPurge cdnPurge;
    private final AffectedPageResolver affectedPages;
    private final ObjectPermissionService permissions;
    private final SsgPublishCoordinator republish;
    private ProgramAccessService programAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProgramAccess(ProgramAccessService programAccess) {
        this.programAccess = programAccess;
    }

    public AccessInvalidationService(RenderDatasetCache datasetCache, CdnPurge cdnPurge,
                                     AffectedPageResolver affectedPages, ObjectPermissionService permissions,
                                     SsgPublishCoordinator republish) {
        this.datasetCache = datasetCache;
        this.cdnPurge = cdnPurge;
        this.affectedPages = affectedPages;
        this.permissions = permissions;
        this.republish = republish;
    }

    public void apply(AccessControlChangedEvent event) {
        if (event == null || event.siteId() == null) return;
        if (programAccess != null) programAccess.invalidateAll();
        datasetCache.clear(RenderDatasetCache.Region.CONTENT);
        List<String> paths = pathsOf(event);
        cdnPurge.purge(event.siteId(), paths);
        if (!stillAnonymousPublic(event)) {
            log.info("access change removed anonymous visibility site={} object={}/{}",
                event.siteId(), event.objectType(), event.objectId());
            republish.requestRebuild(event.siteId());
        }
    }

    private List<String> pathsOf(AccessControlChangedEvent event) {
        if (!ObjectType.CONTENT.equals(event.objectType()) || event.objectId() == null) {
            return List.of("/");
        }
        List<String> paths = new ArrayList<>();
        for (AffectedPageResolver.AffectedPage page : affectedPages.resolve(event.siteId(), event.objectId())) {
            if (page.routePath() != null && !page.routePath().isBlank()) paths.add(page.routePath());
        }
        return paths.isEmpty() ? List.of("/") : List.copyOf(paths);
    }

    private boolean stillAnonymousPublic(AccessControlChangedEvent event) {
        if (event.objectType() == null || event.objectId() == null) return false;
        return permissions.hasAnonymousRight(event.objectType(), event.objectId(), ObjectType.VIEW);
    }
}
