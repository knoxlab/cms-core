package com.powercms.cms.identity.dto;

public record UpdateSiteAuthorizationPolicyRequest(
    Boolean peerReviewRequired,
    Boolean reviewerCanWrite,
    Boolean publisherCanWrite,
    Boolean agentDirectPublish,
    Boolean memberRegistrationReview
) {}
