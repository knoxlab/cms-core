-- FR-AI-GOV-003：内容版本元数据标记 AI 参与（版本历史标注 AI 版本；回滚走既有版本机制，
-- 历史 AI 版本在版本链中保留 AI 身份，回滚后 AI 标记仍可查）。
alter table content_version
    add column ai_generated «bool» not null default «lit_f»;
alter table content_version
    add column ai_model varchar(200);
alter table content_version
    add column ai_prompt_version integer;
alter table content_version
    add column ai_generated_at «ts»;
