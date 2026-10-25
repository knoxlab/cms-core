create table site_navigation_config (
    site_id «uuid» primary key references site(id),
    additional_fields «txt» not null «def_arr»,
    updated_by varchar(100),
    updated_at «ts» not null «def_now»
);

create table url_rewrite_rule (
    id «uuid» primary key,
    site_id «uuid» not null references site(id),
    name varchar(200) not null,
    pattern varchar(512) not null,
    replacement varchar(1000) not null,
    priority integer not null default 0,
    status varchar(20) not null default 'DRAFT',
    lock_version bigint not null default 0,
    created_by varchar(100),
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    «uq_ns_name»
);
create index idx_url_rewrite_site_status_priority on url_rewrite_rule(site_id, status, priority);
