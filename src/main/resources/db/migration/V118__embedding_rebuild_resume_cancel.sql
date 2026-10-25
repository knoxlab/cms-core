-- 阶段三 NFR-AI-PERF-003 / FR-AI-RAG-001 缺口收尾（G9-⑩）：
-- 原登记遗留：索引重建「条目级断点续跑 / 取消 + 影子索引分批写入」未实现，且候选文档全量驻留内存。
-- 本迁移为重建进度表补齐**游标 + 候选索引 + 协作式取消 + 心跳**四组状态，支撑：
--   ① cursor_updated_at / cursor_id：keyset 分页游标（与扫描排序 (updated_at, id) 一致），
--      每批次写入后先落游标再取下一页；崩溃/取消后续跑从游标继续，不重扫已完成批次；
--   ② candidate_index：本次影子索引的物理索引名（续跑复用，全新重建换新名后重置）；
--   ③ cancel_requested：协作式取消标记（由批次循环在页边界观察后落 CANCELLED，保留游标可续跑）；
--   ④ heartbeat_at：批次心跳；RUNNING 且心跳早于阈值视为「进程已死/线程已丢」，允许新的 submit 接管续跑
--      （对齐 G9-⑦ 对 agent_job 停滞 RUNNING 的回收口径）。
-- status 取值新增 CANCELLED（沿用既有 varchar(20)，无需改列）。
alter table embedding_rebuild_progress add column candidate_index varchar(200);
alter table embedding_rebuild_progress add column cursor_updated_at «ts»;
alter table embedding_rebuild_progress add column cursor_id «uuid»;
alter table embedding_rebuild_progress add column cancel_requested «bool» not null default «lit_f»;
alter table embedding_rebuild_progress add column heartbeat_at «ts»;
