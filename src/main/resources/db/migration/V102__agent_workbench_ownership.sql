-- FR-AI-AGENT-006/007 持久化加固：消息归属字段 + 会话所有权复合外键
-- 目的：在数据库层保证 message/task 的 (session_id, site_id, user_id) 与其所属会话一致，
-- 使站点/用户隔离在仓储层可直接约束，避免仅凭 session_id 造成越权读取。

alter table agent_message add column site_id «uuid»;
alter table agent_message add column user_id «uuid»;

update agent_message set site_id = (select s.site_id from agent_session s where s.id = agent_message.session_id);
update agent_message set user_id = (select s.user_id from agent_session s where s.id = agent_message.session_id);

«alt_setnn_v102_s»;
«alt_setnn_v102_u»;

alter table agent_session add constraint uk_agent_session_owner unique (id, site_id, user_id);

alter table agent_message add constraint fk_agent_message_owner
    foreign key (session_id, site_id, user_id) references agent_session (id, site_id, user_id) on delete cascade;

alter table agent_task add constraint fk_agent_task_owner
    foreign key (session_id, site_id, user_id) references agent_session (id, site_id, user_id) on delete cascade;

alter table agent_message add column task_id «uuid»;
alter table agent_message add constraint fk_agent_message_task foreign key (task_id) references agent_task (id);

alter table agent_task add column commit_result «txt»;

create index idx_agent_message_session_owner on agent_message (session_id, site_id, user_id, created_at);
