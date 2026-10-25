-- 短激活码（docs/license-and-offline-subscription-design.md §3.5，策略 C）：
-- 每年付款后厂商给一串 29 字符的码，把「生效到期时间」推进到
-- 不超过 License 内 max_not_after 的位置；只做延期，不改版本/能力/配额。
--
-- 单独一列而不是覆盖 not_after：
--   1) not_after 是合同事实，必须保持原样便于纠纷核对；
--   2) 短激活码水位与 not_after 取较大值参与状态评估，语义上是「延期水位」；
--   3) 换新授权文件（新 seq）时清空本列，避免旧延期被带到新一代授权。
alter table license_state add column short_code_not_after «ts»;
