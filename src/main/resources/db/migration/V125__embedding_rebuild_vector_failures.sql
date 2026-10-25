-- 域11 知识库端到端实测（2026-09-20）缺陷收尾 DEF-KB-01 / DEF-KB-02：
-- ①DEF-KB-01「向量重建把未发布内容灌入访客索引并切换别名 → 匿名可检索草稿正文」——
--   本迁移只增计量列；真正的闸门是应用层三处（扫描人群收敛到 PUBLISHED、切别名前的非发布占比校验、
--   访客检索按 status 二次过滤）。
-- ②DEF-KB-02「embedding 不可用时任务态/计数口径矛盾」——向量重建此前把「文档写入成功」当作成功，
--   即使 embedding 全失败（文档按无向量降级写入、失败只落 embedding_dead_letter），
--   终态仍是 COMPLETED 且 succeeded 计入全部条目：任务显示成功、失败队列却堆了成百条 EMBED 死信。
--   本列把「写入成功」与「向量真的生成」拆开计量，任务终态据此判定（超阈值 → FAILED 且不切换别名）。
-- 既有行兼容：默认 0（= 未观测到缺向量），终态与计量语义仅对新一轮任务生效，不影响历史进度行。
alter table embedding_rebuild_progress add column vector_failures int not null default 0;
