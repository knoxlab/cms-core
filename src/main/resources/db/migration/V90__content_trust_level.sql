-- FR-AI-SRC-004：内容可信度治理。
-- ① 内容条目可信等级 trust_level（0=BLOCKED 排除 / 1=LOW / 2=NORMAL / 3=VERIFIED）。
--    AI 引用时：BLOCKED 整条排除；其余按 (trust_level 权威分) 参与可信度加权排序。
--    来源域自动推断默认值：PIM/DAM 同步条目初始 NORMAL（2），人工内容 NORMAL（2），
--    由编辑/管理员手工升降级；null 兼容旧数据按 NORMAL 处理。
ALTER TABLE content_item
    ADD COLUMN trust_level INT NOT NULL DEFAULT 2;

create «idx» idx_content_item_trust
    ON content_item (trust_level);

-- ② 站点级可信度策略（验收：「可信度配置可按站点调整」）。
ALTER TABLE ai_site_policy
    ADD COLUMN citation_min_trust INT NOT NULL DEFAULT 0;

«cm_V90»

-- ③ 内容审批状态口径：content_item 已有 status（IN_REVIEW/APPROVED/PUBLISHED 等），
--    「未审批不可引用」复用既有状态判定（AiTrustGuard），无需新列。
