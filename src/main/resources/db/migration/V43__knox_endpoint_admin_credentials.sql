-- 端点管理员凭据（Knox 上传等写操作需 admin role token；client_credentials 仅授予 api_client role）。
-- admin_password 为 AES-GCM 密文（SecretCryptor），与 client_secret 一致。

alter table knox_endpoint add column admin_username varchar(100);
alter table knox_endpoint add column admin_password varchar(500);
