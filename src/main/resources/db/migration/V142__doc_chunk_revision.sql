-- V142：分块修订历史（FR-AI-KB-016 编辑与回滚）
--
-- 作用：人工编辑分块时保留**编辑前**的文本，使「回滚到上一版本」可行，且重新分块导致的
-- 覆盖也留痕（重新分块会丢弃全部人工编辑，事后需要能查证改了什么）。
--
-- 为什么单独建表而不在 doc_chunk 上加 "previous_content" 列：
--   ① 只存一版无法回滚两次；② 重新分块会删除并重建 doc_chunk 行，
--      挂在行上的历史会随之消失——而「重新分块丢了人工编辑」正是最需要留痕的场景。
--   因此历史必须独立于 doc_chunk 行存在。
--
-- 记录时机（每次覆盖 content 前写一条）：
--   - 人工编辑：source=HUMAN_EDIT
--   - 重新分块前的快照：source=REBUILD
--
-- 方言约束：时间列用 ts/tstz token；大文本用 txt token；**不得**写字面 TIMESTAMPTZ。
-- 注释中只可出现**已定义**的占位符 token——Flyway 会在注释里解析占位符，
-- 未定义的键名会让整条迁移解析失败（本项目曾因此一次性打挂 129 个测试类）。

create table doc_chunk_revision (
    id              «uuid»        not null,
    doc_chunk_id    «uuid»        not null,
    doc_unit_id     «uuid»        not null,
    -- 该修订对应的分块序号（重新分块后原 doc_chunk 行会被删除，靠序号仍可追溯）
    ordinal         integer       not null,
    -- 修订前的文本
    content         «txt»         not null,
    -- 修订前的上下文头（一并回滚，否则文本与标题路径会错配）
    context_header  varchar(1000),
    -- 修订前的内容哈希（回滚后据此重算向量）
    content_hash    varchar(64)   not null,
    -- HUMAN_EDIT | REBUILD
    source          varchar(20)   not null,
    -- 操作者（审计追溯；系统动作记 system）
    actor           varchar(200),
    created_at      «ts»          not null,
    constraint pk_doc_chunk_revision primary key (id)
);

-- 按分块查历史（回滚取最近一条）
create index ix_dcr_chunk on doc_chunk_revision (doc_chunk_id, created_at);
-- 按单元查历史（重新分块后原分块 id 已不存在，只能靠单元 + 序号追溯）
create index ix_dcr_unit  on doc_chunk_revision (doc_unit_id, ordinal, created_at);
