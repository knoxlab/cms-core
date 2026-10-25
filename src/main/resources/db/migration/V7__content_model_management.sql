-- Content model designer and version audit (H2 / PostgreSQL compatible)
alter table content_type add column template_type varchar(20) not null default 'PAGE';
alter table content_type add column display_template varchar(500);
alter table content_type add column icon varchar(100);
alter table content_type add column thumbnail varchar(500);
alter table content_type add column published_version int;
alter table content_type add column published_at «ts»;
alter table content_type add column published_by varchar(100);
alter table field_definition add column help_text varchar(1000);
alter table field_definition add column read_only «bool» not null default «lit_f»;
alter table field_definition add column parent_id «uuid»;
alter table field_definition add column field_config «txt»;
alter table field_definition add column condition_expr varchar(1000);
alter table field_definition add constraint fk_field_definition_parent foreign key (parent_id) references field_definition (id);
create table form_layout (id «uuid» primary key, content_type_id «uuid» not null references content_type (id), element_type varchar(20) not null, element_id «uuid» not null references field_definition (id), parent_id «uuid», sort_order int not null default 0, created_at «ts» not null «def_now», unique (content_type_id, element_id));
create index idx_form_layout_type on form_layout (content_type_id);
create index idx_form_layout_parent on form_layout (parent_id);
create table content_type_change_log (id «uuid» primary key, content_type_id «uuid» not null references content_type (id), from_version int, to_version int not null, change_summary «txt» not null, migration_rules «txt», operator varchar(100) not null, operated_at «ts» not null «def_now»);
create index idx_change_log_type on content_type_change_log (content_type_id);
