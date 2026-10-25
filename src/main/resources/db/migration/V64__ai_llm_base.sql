-- AI/LLM 底座（FR-AI-LLM-001~009）：模型配置、加密凭据、提示词模板、用量明细（H2/PG 兼容）

create table ai_model_credential (
    id «uuid» primary key,
    site_id «uuid»,
    secret_enc «txt» not null,
    masked_hint varchar(40) not null,
    status varchar(20) not null default 'ACTIVE',
    valid_from «ts» not null «def_now»,
    valid_to «ts»,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»
);
create index idx_ai_cred_site on ai_model_credential (site_id, status);

create table ai_model_config (
    id «uuid» primary key,
    scope varchar(10) not null default 'global',
    site_id «uuid»,
    provider varchar(100) not null,
    endpoint_type varchar(20) not null default 'public',
    capability varchar(20) not null,
    model_name varchar(200) not null,
    base_url varchar(1000),
    credential_id «uuid»,
    priority integer not null default 0,
    enabled «bool» not null default «lit_t»,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»
);
create index idx_ai_config_site_cap on ai_model_config (site_id, capability, enabled, priority);
create index idx_ai_config_global_cap on ai_model_config (scope, capability, enabled, priority);

create table ai_prompt_template (
    id «uuid» primary key,
    scene_key varchar(100) not null,
    version integer not null,
    content «txt» not null,
    status varchar(20) not null default 'DRAFT',
    baseline «bool» not null default «lit_f»,
    approval_id «uuid»,
    created_by varchar(100),
    created_at «ts» not null «def_now»,
    unique (scene_key, version)
);
create index idx_ai_prompt_scene on ai_prompt_template (scene_key, status);

create table ai_usage_record (
    id «uuid» primary key,
    site_id «uuid»,
    user_id varchar(100),
    agent_token_id «uuid»,
    model varchar(200) not null,
    capability varchar(20) not null,
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    latency_ms bigint not null default 0,
    status varchar(20) not null,
    fallback_from varchar(200),
    prompt_scene varchar(100),
    prompt_version integer,
    created_at «ts» not null «def_now»
);
create index idx_ai_usage_site_time on ai_usage_record (site_id, created_at);
create index idx_ai_usage_model on ai_usage_record (model, created_at);
