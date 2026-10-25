package com.powercms.cms.publish;

import java.util.UUID;

/** Release 即将标记为 DEPLOYED，访客索引门禁在 AFTER_COMMIT 后校验（FR-SEARCH-007）。 */
public record ReleaseDeployRequestedEvent(UUID releaseId, UUID siteId) {}
