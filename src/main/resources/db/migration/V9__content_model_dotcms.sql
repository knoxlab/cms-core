-- ============================================================
-- P2：dotCMS 内容建模借鉴——内容关系、字段变量、类型复制、系统字段、唯一性
-- H2（本地开发，PostgreSQL 模式）与 PostgreSQL 兼容语法
-- ============================================================

-- 内容关系定义（FR-CM-RELATION-001~014，19.1.4 节）
create table content_relation (
    id                «uuid» primary key,
    site_id           «uuid»         not null references site (id),
    relation_name     varchar(200) not null,
    cardinality       varchar(20)  not null, -- ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY
    source_type_id    «uuid»         not null references content_type (id),
    target_type_id    «uuid»         not null references content_type (id),
    description       varchar(500),
    created_by        varchar(100) not null,
    created_at        «ts»    not null «def_now»,
    «uq_v9_rel»
);

create index idx_relation_source on content_relation (source_type_id);
create index idx_relation_target on content_relation (target_type_id);

-- 内容关系实例（关联内容项之间的实际关系数据）
create table content_relation_item (
    id                «uuid» primary key,
    relation_id       «uuid»         not null references content_relation (id),
    source_content_id «uuid»         not null,
    target_content_id «uuid»         not null,
    sort_order        int          not null default 0,
    created_by        varchar(100) not null,
    created_at        «ts»    not null «def_now»,
    unique (relation_id, source_content_id, target_content_id)
);

create index idx_relation_item_source on content_relation_item (relation_id, source_content_id);
create index idx_relation_item_target on content_relation_item (relation_id, target_content_id);

-- 字段定义：系统字段保护（FR-CM-SYSFIELD-001~005）与唯一性（FR-CM-UNIQUE-001）
alter table field_definition add column is_system «bool» not null default «lit_f»;
alter table field_definition add column system_field_type varchar(50);
alter table field_definition add column unique_field «bool» not null default «lit_f»;

-- 内容类型：Widget/Form 子类型（FR-CM-WIDGET-001/FR-CM-FORM-001）、复制来源（FR-CM-COPY-010）
alter table content_type add column subtype varchar(20);
alter table content_type add column copied_from_type_id «uuid»;
alter table content_type add constraint fk_content_type_copied_from foreign key (copied_from_type_id) references content_type (id);
alter table content_type add column form_notification_emails «txt»;
