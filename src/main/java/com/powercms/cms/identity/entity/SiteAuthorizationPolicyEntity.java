package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "site_authorization_policy")
public class SiteAuthorizationPolicyEntity {

    @Id
    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "peer_review_required", nullable = false)
    private boolean peerReviewRequired;

    @Column(name = "reviewer_can_write", nullable = false)
    private boolean reviewerCanWrite;

    @Column(name = "publisher_can_write", nullable = false)
    private boolean publisherCanWrite;

    /** FR-AI-GOV-004：AI 内容直接发布开关（默认 false ⟺ 必须审批）。 */
    @Column(name = "agent_direct_publish", nullable = false)
    private boolean agentDirectPublish;

    /** 会员自助注册：true 需后台审核通过后才能登录；false 注册成功即生效并自动登录。 */
    @Column(name = "member_registration_review", nullable = false)
    private boolean memberRegistrationReview;

    public static SiteAuthorizationPolicyEntity defaults(UUID siteId) {
        SiteAuthorizationPolicyEntity policy = new SiteAuthorizationPolicyEntity();
        policy.siteId = siteId;
        return policy;
    }

    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public boolean isPeerReviewRequired() { return peerReviewRequired; }
    public void setPeerReviewRequired(boolean peerReviewRequired) { this.peerReviewRequired = peerReviewRequired; }
    public boolean isReviewerCanWrite() { return reviewerCanWrite; }
    public void setReviewerCanWrite(boolean reviewerCanWrite) { this.reviewerCanWrite = reviewerCanWrite; }
    public boolean isPublisherCanWrite() { return publisherCanWrite; }
    public void setPublisherCanWrite(boolean publisherCanWrite) { this.publisherCanWrite = publisherCanWrite; }
    public boolean isAgentDirectPublish() { return agentDirectPublish; }
    public void setAgentDirectPublish(boolean agentDirectPublish) { this.agentDirectPublish = agentDirectPublish; }
    public boolean isMemberRegistrationReview() { return memberRegistrationReview; }
    public void setMemberRegistrationReview(boolean memberRegistrationReview) { this.memberRegistrationReview = memberRegistrationReview; }
}
