-- IAM P0：角色范围/自定义能力、站点授权策略（对齐 identity-access-specification.md 第 8 章）

alter table sys_role add column scope varchar(10) not null default 'SITE';
alter table sys_role add column capabilities varchar(1000);

update sys_role
set scope = 'ORG'
where lower(code) in ('system-admin', 'migration-admin', 'migration-auditor');

create table site_authorization_policy (
    site_id «uuid» primary key references site (id),
    peer_review_required «bool» not null default «lit_f»,
    reviewer_can_write «bool» not null default «lit_f»,
    publisher_can_write «bool» not null default «lit_f»
);
