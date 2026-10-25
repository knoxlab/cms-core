alter table site_container add column current_version integer not null default 0;

create table site_container_revision (
    id «uuid» primary key,
    container_id «uuid» not null references site_container(id) on delete cascade,
    version integer not null,
    parent_version integer,
    author varchar(100),
    message varchar(500),
    snapshot «txt» not null,
    created_at «ts» not null «def_now»,
    unique(container_id, version)
);
create index idx_container_revision_container on site_container_revision(container_id, version desc);
