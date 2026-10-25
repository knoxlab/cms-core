-- IAM P1：会话登录配套字段、密码历史、API Token（identity-access-specification.md 8.3 / 8.5）

alter table sys_user add column password_changed_at «ts»;
alter table sys_user add column externally_managed «bool» not null default «lit_f»;
alter table sys_user add column last_login_at «ts»;
alter table sys_user add column last_login_ip varchar(64);

update sys_user set password_changed_at = current_timestamp where password_changed_at is null;

create table sys_user_password_history (
    id            «uuid» primary key,
    user_id       «uuid»         not null references sys_user (id),
    password_hash varchar(200) not null,
    created_at    «ts»    not null «def_now»
);

create index idx_sys_user_password_history_user on sys_user_password_history (user_id, created_at);

create table api_token (
    id            «uuid» primary key,
    public_id     varchar(32)  not null unique,
    user_id       «uuid»         not null references sys_user (id),
    site_id       «uuid»         references site (id),
    name          varchar(100) not null,
    token_hash    varchar(64)  not null,
    scopes        varchar(1000),
    expires_at    «ts»    not null,
    allowed_from  varchar(500),
    revoked_at    «ts»,
    created_by    «uuid»         references sys_user (id),
    created_at    «ts»    not null «def_now»
);

create index idx_api_token_user on api_token (user_id, created_at);
