-- P2-2 safe declarative MVC controller registry and immutable published versions.
create table site_controller (
    id «uuid» primary key,
    site_id «uuid» not null references site(id),
    path varchar(300) not null,
    route_path varchar(500) not null,
    http_method varchar(10) not null,
    definition «txt» not null,
    status varchar(20) not null default 'DRAFT',
    current_version integer not null default 0,
    published_version integer,
    lock_version bigint not null default 0,
    created_by varchar(100),
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    «uq_v21_path»,
    «uq_v21_route»
);
create index idx_site_controller_route on site_controller(site_id, route_path, http_method, status);

create table site_controller_version (
    id «uuid» primary key,
    controller_id «uuid» not null references site_controller(id),
    version integer not null,
    definition «txt» not null,
    definition_digest varchar(64) not null,
    author varchar(100),
    message varchar(500),
    created_at «ts» not null «def_now»,
    unique(controller_id, version)
);
create index idx_site_controller_version_controller on site_controller_version(controller_id);
