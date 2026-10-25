-- FR-AI-KB-042② / ADR-17：跨源融合开关（站点级，默认关闭）。
--
-- 为什么需要独立开关而不是"配了 source_weights 就自动启用"：
-- 串行回退（现状）与跨源融合对同一个问题会给出不同答案 ——
-- 前者是「首选来源无命中才回退」，后者是「多源同时参与排序」。
-- ADR-17 要求「既有单源内容检索路径保持不变（零回归）」，
-- 因此不能因为站点填了几个权重就把它的检索语义换掉。
--
-- 默认 false 保证升级后所有站点的检索行为逐字不变。
alter table ai_site_policy add column source_fusion_enabled boolean not null default false;
