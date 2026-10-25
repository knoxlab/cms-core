-- V141：知识库单元的分块索引计数（FR-AI-KB-030 ~ 037）
--
-- 背景：V138 已建 doc_unit.chunk_count（分块总数）与 chunk_vector_count（向量成功数），
-- 但缺少「已写入检索索引的块数」。三者口径不同，缺一个就无法回答
-- 「块都在、向量都生成了，为什么检索不到」——索引写入失败时会表现为
-- 检索结果莫名缺失，而运维面看到 chunk_vector_count 满值，无从定位。
--
-- 因此补齐 indexed_chunk_count，使「分块 → 向量 → 索引」三段链路各有可观测口径。
--
-- 方言约束：列用 integer + not null default；**不得**写字面 TIMESTAMPTZ；
-- 注释中只可出现**已定义**的占位符 token（Flyway 会在注释里解析占位符，
-- 未定义的键名会让整条迁移解析失败——本项目曾因此一次性打挂 129 个测试类）。

alter table doc_unit add column indexed_chunk_count integer not null default 0;
