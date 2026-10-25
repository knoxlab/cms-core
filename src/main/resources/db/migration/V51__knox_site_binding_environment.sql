-- FR-KNX-005 多发布环境映射（V40 初版绑定按站点唯一，现升级为站点×环境唯一）：
-- preview/staging/live 环境可各自绑定不同 Knox 端点/库；历史单绑定数据归入 PREVIEW 环境。
alter table knox_site_binding add column environment varchar(20) not null default 'PREVIEW';
«alt_dropc_v51»;
alter table knox_site_binding add constraint uq_knox_binding_site_env unique (site_id, environment);
