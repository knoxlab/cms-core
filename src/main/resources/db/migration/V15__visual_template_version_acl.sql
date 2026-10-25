-- Visual template immutable revisions + per-role ACL (P1-4 / P1-5)
alter table site_template add column current_version integer not null default 0;
alter table site_template add column lock_version bigint not null default 0;

create table site_template_revision (
    id «uuid» primary key,
    template_id «uuid» not null references site_template(id),
    version integer not null,
    parent_version integer,
    author varchar(100),
    message varchar(500),
    snapshot «txt» not null,
    created_at «ts» not null «def_now»,
    unique(template_id, version)
);
create index idx_site_template_revision_template on site_template_revision(template_id);

create table site_template_acl (
    id «uuid» primary key,
    template_id «uuid» not null references site_template(id),
    role_code varchar(50) not null,
    can_view «bool» not null default «lit_t»,
    can_use «bool» not null default «lit_f»,
    can_edit «bool» not null default «lit_f»,
    can_publish «bool» not null default «lit_f»,
    unique(template_id, role_code)
);
create index idx_site_template_acl_template on site_template_acl(template_id);
