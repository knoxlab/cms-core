-- IAM P0/P1 预留：双登录通道（kind / loginMode）、通道角色、后台布局、对象 ACL 数据模型
-- 对齐 identity-access-specification.md 5.1 / 5.4 / 8.6 / 8.7

-- 5.1 sys_user 扩展：kind / home_site_id / profile_content_id / last_login_mode
alter table sys_user add column kind varchar(10) not null default 'BACKEND';
alter table sys_user add column home_site_id «uuid»;
alter table sys_user add constraint fk_sys_user_home_site foreign key (home_site_id) references site (id);
alter table sys_user add column profile_content_id «uuid»;
alter table sys_user add column last_login_mode varchar(10);

-- 8.7 后台布局与角色绑定
create table sys_layout (
    id   «uuid» primary key,
    code varchar(60)  not null unique,
    name varchar(120) not null
);

create table sys_role_layout (
    id        «uuid» primary key,
    role_id   «uuid» not null references sys_role (id),
    layout_id «uuid» not null references sys_layout (id),
    constraint uq_role_layout unique (role_id, layout_id)
);

insert into sys_layout (id, code, name) values
    ('10000000-0000-0000-0000-000000000001', 'content',      '内容'),
    ('10000000-0000-0000-0000-000000000002', 'model',        '模型'),
    ('10000000-0000-0000-0000-000000000003', 'media',        '媒体'),
    ('10000000-0000-0000-0000-000000000004', 'workflow',     '工作流'),
    ('10000000-0000-0000-0000-000000000005', 'template',     '模板'),
    ('10000000-0000-0000-0000-000000000006', 'publish',      '发布'),
    ('10000000-0000-0000-0000-000000000007', 'users',        '用户'),
    ('10000000-0000-0000-0000-000000000008', 'site-settings','站点设置'),
    ('10000000-0000-0000-0000-000000000009', 'audit',        '审计'),
    ('10000000-0000-0000-0000-00000000000a', 'migrate',      '迁移');

-- 默认布局绑定（幂等：启动补种以角色 code 为准）
insert into sys_role_layout (id, role_id, layout_id)
select «fn_uuid», r.id, l.id
from sys_role r
cross join sys_layout l
where lower(r.code) = 'system-admin'
  and l.code in ('content','model','media','workflow','template','publish','users','site-settings','audit','migrate')
  and not exists (
      select 1 from sys_role_layout b where b.role_id = r.id and b.layout_id = l.id
  );

-- 8.6 对象 ACL（P1，数据模型预留）：首期全部站点能力覆盖，等价全部 inherit=true
create table object_acl (
    id             «uuid» primary key,
    site_id        «uuid» references site (id),
    object_type    varchar(30)  not null,
    object_id      «uuid»         not null,
    principal_type varchar(10)  not null,
    principal_id   «uuid»         not null,
    permissions    varchar(1000) not null,
    created_by     «uuid» references sys_user (id),
    created_at     «ts»    not null «def_now»,
    constraint uq_object_acl unique (object_type, object_id, principal_type, principal_id)
);

create index idx_object_acl_object on object_acl (object_type, object_id);
