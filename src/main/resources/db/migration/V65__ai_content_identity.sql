-- AI 内容标识（FR-AI-GOV-001/007）：content_item 增加 AI 参与标识字段（H2/PG 兼容）
alter table content_item
    add column ai_generated «bool» not null default «lit_f»;
alter table content_item
    add column ai_model varchar(200);
alter table content_item
    add column ai_prompt_version integer;
alter table content_item
    add column ai_generated_at «ts»;
