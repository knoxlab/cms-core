package com.powercms.cms.content;

import java.util.UUID;

/** Domain event emitted when content is meaningfully published. */
public record ContentPublishedEvent(
    UUID eventId,
    UUID siteId,
    UUID contentItemId,
    UUID contentTypeId,
    String routePath,
    int publishedVersion,
    String previousStatus
) {}
