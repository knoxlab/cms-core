-- 会员自助注册：false（默认）注册即通过并允许登录；true 提交后为 PENDING，需后台通过。
alter table site_authorization_policy
    add column member_registration_review «bool» not null default «lit_f»;
