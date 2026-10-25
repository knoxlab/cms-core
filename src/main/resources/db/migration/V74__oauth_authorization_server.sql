-- FR-AGT-MCP-013 / identity §9.5.1：OAuth 授权服务器（Agent/MCP 接入，OAuth 2.1 授权码模式）。
-- 与 FR-IAM-005 API Token 共用同一权限校验面（token 验签/有效期/吊销 → scope 求交 → 用户角色 → 对象级 ACL）。

-- OAuth 客户端注册（client_id 公开、client_secret 只存 SHA-256；机密管理，纳入系统管理员审计）
create table oauth_client (
    id                 «uuid» primary key,
    client_id          varchar(64)  not null unique,
    client_secret_hash varchar(64)  not null,
    name               varchar(100) not null,
    redirect_uris      varchar(2000) not null,
    scopes             varchar(1000),
    revoked_at         «ts»,
    created_by         «uuid»         references sys_user (id),
    created_at         «ts»    not null «def_now»
);

create index idx_oauth_client_client_id on oauth_client (client_id);

-- 授权码（短期、一次性；绑定 client/redirect_uri/user/scopes/resource + PKCE code_challenge）
create table oauth_authorization (
    id                   «uuid» primary key,
    code_hash            varchar(64)  not null unique,
    client_id            «uuid»         not null references oauth_client (id),
    user_id              «uuid»         not null references sys_user (id),
    redirect_uri         varchar(2000) not null,
    scopes               varchar(1000),
    resource             varchar(500),
    code_challenge       varchar(128),
    code_challenge_method varchar(10),
    expires_at           «ts»    not null,
    consumed_at          «ts»,
    created_at           «ts»    not null «def_now»
);

create index idx_oauth_authorization_code on oauth_authorization (code_hash);

-- Access token 与 Refresh token（opaque，存哈希、可吊销；绑定 resource + DPoP cnf.jkt）
create table oauth_token (
    id          «uuid» primary key,
    token_hash  varchar(64)  not null unique,
    kind        varchar(10)  not null, -- ACCESS / REFRESH
    client_id   «uuid»         not null references oauth_client (id),
    user_id     «uuid»         not null references sys_user (id),
    scopes      varchar(1000),
    resource    varchar(500),
    site_id     «uuid»,
    cnf_jkt     varchar(64),
    parent_hash varchar(64), -- REFRESH → 关联其 ACCESS token 哈希
    expires_at  «ts»    not null,
    revoked_at  «ts»,
    created_at  «ts»    not null «def_now»
);

create index idx_oauth_token_hash on oauth_token (token_hash);
create index idx_oauth_token_user on oauth_token (user_id, created_at);
