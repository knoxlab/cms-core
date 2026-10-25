-- V143：向量缓存（FR-AI-KB-024 跨文档复用）
--
-- 作用：按「站点 + embedding 模型 + 维度 + 内容哈希」缓存已算出的向量，
-- 使**同一份内容在多个文档/单元/版本中出现时只调用一次 embedding**。
--
-- 为什么需要它（而不是复用 doc_chunk 上已有的向量）：
--   向量**不落 doc_chunk**（维度随模型变化，列类型无法固定），
--   因此 `findVectorizedByContentHash` 只能回答"存在可复用块"，
--   **拿不到向量值**。据此跳过 embedding 就会写出零维度向量 —— 比不去重更糟。
--   要做跨文档复用，向量必须有一个可检索的持久化载体，这就是本表。
--
-- 为什么 key 里必须含 model + dimensions（而不是只用 content_hash）：
--   同一个 content_hash 在不同 embedding 模型下对应**不同语义空间**的向量。
--   只按 hash 命中会把 A 模型的向量当成 B 模型的结果用 ——
--   与 FR-AI-KB-025「查询与文档向量同源」直接冲突，
--   且症状是"检索结果莫名变差"而非报错。必须由 key 保证不可能命中错模型。
--
-- 为什么是 (site_id, model, dimensions, content_hash) 唯一而不是全局唯一：
--   向量与站点模型配置绑定；跨站点复用会把 A 站点模型的向量写进 B 站点索引。
--
-- 为什么存 bytea 而不是 float[] 列：
--   本项目无数组列类型（MySQL/达梦兼容），且向量长度随维度变化。
--   以 IEEE-754 单精度小端字节序存储，读取时按 dimensions 校验长度。
--
-- 方言约束：时间列用 ts/tstz token；二进制用 blob token；**不得**写字面 TIMESTAMPTZ。
-- 注释中只可出现**已定义**的占位符 token —— Flyway 会在注释里解析占位符，
-- 未定义的键名会让整条迁移解析失败。

create table doc_embedding_cache (
    id              «uuid»        not null,
    site_id         «uuid»        not null,
    -- embedding 模型标识（EmbeddingClient.model()），与 doc_chunk 的 embedding_version 同源
    model           varchar(128)  not null,
    -- 向量维度：必须与 model 一致，用于读出时校验字节长度
    dimensions      integer       not null,
    -- 与 doc_chunk.content_hash 同口径（含上下文头的 embedding_text 之哈希）
    content_hash    varchar(64)   not null,
    -- IEEE-754 单精度小端字节序，长度 = dimensions * 4
    vector          «blob»        not null,
    -- 命中计数与最近命中时间：用于运维判断缓存是否真的在起作用
    -- （若无命中，说明内容哈希口径或去重键有问题，而不是"内容本来就不重复"）
    hit_count       integer       not null default 0,
    last_hit_at     «tstz»,
    created_at      «ts»          not null,
    updated_at      «ts»          not null,
    constraint pk_doc_embedding_cache primary key (id),
    constraint uq_doc_embedding_cache_key unique (site_id, model, dimensions, content_hash)
);

-- 复用查询按 (site_id, model, dimensions, content_hash) 精确匹配 → 唯一约束已覆盖。
-- 另需按站点清理（模型更换后旧模型的缓存应可批量清理），故补 site_id 索引。
create index idx_doc_embedding_cache_site on doc_embedding_cache (site_id);
