-- V144：向量缓存的查询统计（FR-AI-KB-024 缓存效果可观测性）
--
-- 作用：按 (站点, 模型, 维度) 累计"查了多少次 / 命中多少次"，
-- 使"缓存是否真的在省 embedding 调用"成为一个**可回答**的问题。
--
-- 为什么必须单独一张表而不能只看 doc_embedding_cache：
--   未命中 = 没有缓存行，**无处记录**。而"有缓存行但一直不命中"恰恰是最需要
--   被发现的失效形态：缓存键口径漂移（例如 embedding_text 构造方式改变）会导致
--   缓存**静默地永不命中** —— 表现为"钱照花、表照涨"，而表里只有已存在的行，
--   看不出"查了多少次"。只有累计查询次数作分母，才能算出命中率并暴露这种情况。
--
-- 为什么 key 含 model + dimensions（与 doc_embedding_cache 同口径）：
--   换模型后旧模型的命中率既无意义也不该与新模型混在一起统计。
--
-- 方言约束：时间列用 ts/tstz token；注释中只可出现**已定义**的占位符 token。

create table doc_embedding_cache_stat (
    id              «uuid»        not null,
    site_id         «uuid»        not null,
    model           varchar(128)  not null,
    dimensions      integer       not null,
    -- 查询次数（分母）：命中与未命中都计
    lookup_count    bigint        not null default 0,
    -- 命中次数（分子）
    hit_count       bigint        not null default 0,
    first_lookup_at «tstz»,
    last_lookup_at  «tstz»,
    created_at      «ts»          not null,
    updated_at      «ts»          not null,
    constraint pk_doc_embedding_cache_stat primary key (id),
    constraint uq_doc_embedding_cache_stat_key unique (site_id, model, dimensions)
);

create index idx_doc_embedding_cache_stat_site on doc_embedding_cache_stat (site_id);
