-- FR-AI-GOV-007 深度合成内容显式标识：站点级「AI 生成」角标/水印开关（默认关闭）。
-- 显式标识 = 前台在 AI 生成内容旁渲染「AI 生成」角标（站点可配）；隐式标识 =
-- content_item.ai_generated/ai_model/ai_prompt_version/ai_generated_at 随 API 响应与导出保留。
alter table ai_site_policy
    add column badge_enabled «bool» not null default «lit_f»;
