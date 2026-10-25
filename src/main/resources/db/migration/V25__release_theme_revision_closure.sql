-- Evolve frozen theme pins to immutable revision closure records.
alter table release_theme_version add column theme_revision_id «uuid»;
alter table release_theme_version add column reference_role varchar(20);
alter table release_theme_version add column file_digest varchar(64);

update release_theme_version rtv
set theme_revision_id = (
 select str.id
 from site_theme_revision str
 where str.theme_id = rtv.theme_id
   and str.version = rtv.theme_version
);
update release_theme_version set file_digest = digest;
update release_theme_version set reference_role = 'ROOT';

-- SET NOT NULL intentionally fails if any historical pin could not be resolved.
«alt_setnn_v25_tr»;
«alt_setnn_v25_rr»;
«alt_setnn_v25_fd»;

alter table release_theme_version add constraint fk_release_theme_version_revision
 foreign key (theme_revision_id) references site_theme_revision(id);
alter table release_theme_version add constraint fk_release_theme_version_theme_version
 foreign key (theme_id, theme_version) references site_theme_revision(theme_id, version);
alter table release_theme_version add constraint ck_release_theme_version_reference_role
 check (reference_role in ('ROOT', 'PARENT'));
alter table release_theme_version add constraint uk_release_theme_version_revision
 unique (release_id, theme_revision_id);

create index idx_release_theme_version_revision on release_theme_version(theme_revision_id);

alter table release_theme_version drop column digest;
