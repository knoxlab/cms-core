-- 多语言（i18n）：用户自助语言偏好，取值受 SupportedLocales 白名单约束（zh-CN/en-US/ja-JP/ko-KR/fr-FR/es-ES）。
-- 默认 NULL = 跟随组织设置（organization_settings.default_language），登录后由前端设置显式覆盖。
alter table sys_user add column language varchar(20);
