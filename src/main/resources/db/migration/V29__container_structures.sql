create table site_container_structure (
    id «uuid» primary key,
    container_id «uuid» not null references site_container(id) on delete cascade,
    structure_id «uuid» not null references content_type(id),
    code «txt» not null,
    sort_order integer not null default 0,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    unique(container_id, structure_id)
);
create index idx_container_structure_container on site_container_structure(container_id, sort_order);
create index idx_container_structure_type on site_container_structure(structure_id);

-- Backfill the existing JSON representation. H2 and PostgreSQL both support string predicates,
-- but JSON extraction differs, so application compatibility code completes legacy migration on save.
