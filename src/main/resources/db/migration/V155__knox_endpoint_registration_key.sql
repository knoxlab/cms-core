-- Knox-Media 启动自注册：按凭据身份去重，已登记则忽略且不改已协商参数。
alter table knox_endpoint add column registration_key varchar(120);
create index ix_knox_endpoint_registration_key on knox_endpoint (registration_key);
