-- Knox-Media 附属组件授权（专业版以上）：
--   1) knox_endpoint.grant_secret：注册时下发的 grant 请求认证密钥（AES-GCM 密文，
--      与 client_secret 同存储口径）；Knox 每次领 grant 用它做 HMAC 请求签名。
--   2) knox_grant_key：CMS 运行时 Ed25519 授权签名密钥对（≠产品 License 离线私钥，§信任模型）。
--      私钥 AES-GCM 加密落库；公钥随注册/ignored 响应下发给 Knox 做 grant 验签。
alter table knox_endpoint add column grant_secret varchar(500);

create table knox_grant_key (
    id             «uuid» primary key,
    key_id         varchar(64) not null,
    public_key     «txt» not null,
    private_key_encrypted varchar(2000) not null,
    created_at     «ts» not null «def_now»,
    constraint uq_knox_grant_key_id unique (key_id)
);
