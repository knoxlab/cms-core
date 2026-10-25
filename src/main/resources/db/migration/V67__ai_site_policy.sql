-- FR-AI-GOV-006：站点级 AI 合规开关（§11.5 开关矩阵）
-- 总开关（enabled）+ 分项开关（generate 生成 / chat 问答 / search 检索）。
-- 行缺失视为继承全局配置（powercms.ai.enabled，默认 false）；创建即默认全开（站点管理员显式关闭）。
create table ai_site_policy (
    site_id «uuid» primary key references site (id),
    enabled «bool» not null default «lit_t»,
    generate_enabled «bool» not null default «lit_t»,
    chat_enabled «bool» not null default «lit_t»,
    search_enabled «bool» not null default «lit_t»,
    updated_by varchar(100),
    updated_at «ts» not null «def_now»
);
