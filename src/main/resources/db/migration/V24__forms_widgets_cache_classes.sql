create table form_definition (
 id «uuid» primary key, site_id «uuid» not null references site(id), name varchar(200) not null, code varchar(100) not null,
 schema_json «txt» not null, content_type_id «uuid» not null references content_type(id), template_path varchar(500) not null,
 controller_id «uuid», status varchar(20) not null default 'DRAFT', current_version integer not null default 0,
 lock_version bigint not null default 0, created_by varchar(100), created_at «ts» not null «def_now»,
 updated_at «ts» not null «def_now», unique(site_id, code)
);
create table form_definition_revision (
 id «uuid» primary key, form_id «uuid» not null references form_definition(id), version integer not null,
 snapshot_json «txt» not null, created_by varchar(100), created_at «ts» not null «def_now»,
 unique(form_id, version)
);
create table form_submission_audit (
 id «uuid» primary key, form_id «uuid» not null references form_definition(id), content_item_id «uuid» not null references content_item(id),
 site_id «uuid» not null references site(id), ip_hash varchar(128), security_summary varchar(1000), created_at «ts» not null «def_now»
);
create table widget_definition (
 id «uuid» primary key, site_id «uuid» not null references site(id), code varchar(100) not null, name varchar(200) not null,
 controller_definition «txt» not null, parameter_schema «txt» not null, status varchar(20) not null default 'DRAFT',
 created_by varchar(100), created_at «ts» not null «def_now», updated_at «ts» not null «def_now»,
 unique(site_id, code)
);
create index idx_form_submission_form on form_submission_audit(form_id, created_at);
create index idx_widget_site_status on widget_definition(site_id, status);
