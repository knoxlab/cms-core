-- FR-AI-SRC-004：引用闸门故障策略（验收偏差登记 #4 —— 区分可降级故障与必须 fail-closed 的违规）。
-- 背景：原实现为「闸门读取异常一律降级放行」，与「过期/未审批/低可信不可引用」硬约束冲突。
-- 口径：
--   FAIL_CLOSED（默认）：治理读取异常时宁可全部排除，绝不把未校验内容放进模型上下文/引用；
--   DEGRADED：治理读取异常时降级放行（仅用于可用性优先且已接受风险的站点）。
-- 注意：数据本身违规（低可信/未审批/过期）在任何模式下都恒被排除——本列只影响「读取异常」路径。
ALTER TABLE ai_site_policy
    ADD COLUMN citation_gate_mode VARCHAR(20) NOT NULL DEFAULT 'FAIL_CLOSED';

«cm_V110»
