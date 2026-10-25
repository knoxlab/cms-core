-- FR-AI-SRC-002 DAM 资产源接入（AI 侧口径）：资产有效期元数据与引用排除。
-- 验收标准（规划 §6.9）：「资产元数据（版本/版权/有效期）可追溯；过期资产不进入 AI 引用」。
-- 版本/版权已有列（media_version 账本 / copyright），本迁移补「有效期」两列：
--   valid_from / valid_until —— 资产可被引用的有效窗口（null = 不限）。
-- 语义：AI 问答引用/检索快照组装时，凡 valid_until < now 或 valid_from > now 的资产视为过期，
-- 由应用层 MediaCitationGuard 排除（索引侧不做时间敏感过滤，避免定时重刷索引）。

alter table media_asset add column valid_from «ts»;
alter table media_asset add column valid_until «ts»;

create «idx» idx_media_asset_valid_until on media_asset (valid_until);
