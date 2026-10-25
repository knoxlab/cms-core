package com.powercms.cms.identity.dto;

import java.util.UUID;

public record SiteAuthorizationPolicyResponse(
    UUID siteId,
    boolean peerReviewRequired,
    boolean reviewerCanWrite,
    boolean publisherCanWrite,
    boolean agentDirectPublish,
    boolean memberRegistrationReview
) {}
