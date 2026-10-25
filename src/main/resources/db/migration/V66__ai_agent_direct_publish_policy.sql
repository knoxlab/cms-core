-- FR-AI-GOV-004：AI 内容直接发布策略（agent-integration §11.1 / identity-access §8.4 同一开关）
-- agent_direct_publish=false（默认）⟺ AI 内容必须审批后方可发布；true 放行受信任 Agent/发布者直发。
alter table site_authorization_policy
    add column agent_direct_publish «bool» not null default «lit_f»;
