create table agent_session (
 id «uuid» primary key, site_id «uuid» not null references site(id), user_id «uuid» not null references sys_user(id), title varchar(200), status varchar(20) not null default 'ACTIVE', turn_count int not null default 0, created_at «ts» not null «def_now», updated_at «ts» not null «def_now»,
 constraint ck_agent_session_status check (status in ('ACTIVE','ARCHIVED'))
);
create index idx_agent_session_site_user on agent_session(site_id,user_id);
create table agent_message (
 id «uuid» primary key, session_id «uuid» not null references agent_session(id) on delete cascade, role varchar(20) not null, content «txt» not null, intent varchar(100), payload «txt», created_at «ts» not null «def_now»,
 constraint ck_agent_message_role check (role in ('USER','ASSISTANT','SYSTEM'))
);
create index idx_agent_message_session on agent_message(session_id,created_at);
create table agent_task (
 id «uuid» primary key, session_id «uuid» not null references agent_session(id) on delete cascade, message_id «uuid» references agent_message(id), site_id «uuid» not null references site(id), user_id «uuid» not null references sys_user(id), intent varchar(100) not null, status varchar(20) not null default 'PREVIEW', payload «txt» not null, preview_result «txt», idempotency_key varchar(100), error_message varchar(2000), created_at «ts» not null «def_now», updated_at «ts» not null «def_now»,
 constraint ck_agent_task_status check (status in ('PREVIEW','CONFIRMED','REJECTED','FAILED'))
);
create index idx_agent_task_session on agent_task(session_id);
create index idx_agent_task_site_user on agent_task(site_id,user_id);
