-- FR-AI-KB-024：文档向量回填的**站点级**限额（每日 token 上限 + 并发上限）
--
-- 此前只有全局配置（「powercms.ai.daily-backfill-token-quota」/
-- 「powercms.ai.backfill-concurrency」），多站点部署下无法按站点区分：
-- 一个站点做全量重建会把**所有站点**共用的日额度与并发吃满。
--
-- 两列都可空，语义与既有的 monthly_token_quota 保持一致（DEF-AI-04）：
--   null  = 继承全局默认
--   0     = 显式不限制（仅对 token 上限有意义；并发为 0 会让回填永不执行，
--           因此读取侧把并发 <= 0 视为「继承全局」而不是「禁止回填」）
--   >0    = 站点独立生效值
--
-- 「留空 = 继承」与「0 = 不限制」是两种不同语义，不能共用一个哨兵值。
alter table ai_site_policy add column daily_backfill_token_quota bigint;
alter table ai_site_policy add column backfill_concurrency integer;
