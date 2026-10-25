package com.powercms.cms.content;

import java.util.UUID;

public record PageResourceChangedEvent(UUID siteId, UUID pageId, String oldRoutePath, String newRoutePath) {}
