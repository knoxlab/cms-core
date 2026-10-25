-- 访客索引口径统一：向量重建的人群来源从「全站发布态」收敛为「已部署 Release 成员」，
-- 进度行需持久化本轮口径来源的 Release（续跑要求 Release 未变；无部署为空）。
alter table embedding_rebuild_progress add column scope_release_id «uuid»;
