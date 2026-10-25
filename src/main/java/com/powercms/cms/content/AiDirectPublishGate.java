package com.powercms.cms.content;

import com.powercms.cms.identity.entity.SiteAuthorizationPolicyEntity;
import com.powercms.cms.identity.repo.SiteAuthorizationPolicyRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * FR-AI-GOV-004 发布门卫：读取站点授权策略 `agent_direct_publish`（identity-access §8.4 /
 * agent-integration §11.1 同一开关，默认 false）。
 *
 * <p>false（默认）⟺ 站点要求 AI 参与内容必须经审批后方可发布；true ⟺ 放行受信任 Agent/发布者
 * 直接发布（Agent `mcp:publish` scope 校验由 MCP 层叠加，本门卫只负责站点级策略判定）。
 */
@Service
public class AiDirectPublishGate {

    private final SiteAuthorizationPolicyRepository policyRepo;

    public AiDirectPublishGate(SiteAuthorizationPolicyRepository policyRepo) {
        this.policyRepo = policyRepo;
    }

    /** 站点是否放行 AI 内容直接发布；无策略行时按默认 false（必须审批）处理。 */
    public boolean allowsAgentDirectPublish(UUID siteId) {
        return policyRepo.findById(siteId)
            .map(SiteAuthorizationPolicyEntity::isAgentDirectPublish)
            .orElse(false);
    }
}
