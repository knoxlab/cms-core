-- FR-ACCESS-009：站点可打开访问引导（未登录 302 / 已登录无权 403）。默认关闭，对外仍是 404。
ALTER TABLE site
    ADD COLUMN access_guidance_enabled «bool» NOT NULL DEFAULT «lit_f»;
