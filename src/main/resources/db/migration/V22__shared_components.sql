create table shared_component (
    id «uuid» primary key,
    site_id «uuid» not null references site(id),
    path varchar(1000) not null,
    title varchar(500) not null,
    content_type_id «uuid» not null references content_type(id),
    data_json «txt» not null «def_obj»,
    template_path varchar(500),
    status varchar(20) not null default 'DRAFT',
    current_version integer not null default 0,
    lock_version bigint not null default 0,
    created_by varchar(100),
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    «uq_v22»
);
create index idx_shared_component_site_status on shared_component(site_id, status);

create table shared_component_revision (
    id «uuid» primary key,
    component_id «uuid» not null references shared_component(id),
    version integer not null,
    parent_version integer,
    snapshot «txt» not null,
    author varchar(100),
    message varchar(500),
    created_at «ts» not null «def_now»,
    unique(component_id, version)
);
create index idx_shared_component_revision_component on shared_component_revision(component_id, version);
