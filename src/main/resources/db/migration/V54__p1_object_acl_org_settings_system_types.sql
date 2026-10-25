-- IAM P1：对象 ACL 继承标志、系统内容类型标记、组织安全与品牌配置
-- 对齐 identity-access-specification.md 4.7 / 5.5 / 8.6 / 8.8

-- 8.6 对象 ACL 对象级继承标志（行级表冗余；同对象所有行取值一致）
alter table object_acl add column inherit «bool» not null default «lit_t»;

-- 8.9 系统内容类型标记（system=true 不可删除/改 code/改 objectType）
alter table content_type add column is_system «bool» not null default «lit_f»;

-- 8.8 组织安全与品牌配置（1:1 organization，第 5.5 节字段）
create table organization_settings (
    id                  «uuid» primary key references organization (id),
    login_identifier    varchar(20) not null default 'USERNAME',
    portal_url          varchar(500),
    mail_domain         varchar(200),
    from_email          varchar(320),
    default_language    varchar(20),
    default_timezone    varchar(60),
    branding            varchar(2000),
    updated_at          «ts» not null «def_now»
);
