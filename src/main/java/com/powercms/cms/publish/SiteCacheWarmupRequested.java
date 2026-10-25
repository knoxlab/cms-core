package com.powercms.cms.publish;
import java.util.UUID;
public record SiteCacheWarmupRequested(UUID siteId, String reason) {}
