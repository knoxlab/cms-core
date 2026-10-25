-- 站点级月 Token 配额覆盖（null = 继承全局 powercms.ai.monthly-token-quota）。
ALTER TABLE ai_site_policy ADD COLUMN monthly_token_quota BIGINT;
