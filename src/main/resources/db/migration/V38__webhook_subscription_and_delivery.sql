-- P2 工作流与交付：Webhook 订阅与投递（FR-API-007）
-- 订阅保存签名密钥（HMAC-SHA256 出签需要还原原文）；事件过滤为逗号分隔白名单，空表示全部事件。
-- 投递表即死信载体：status=DEAD 表示超过最大重试次数的死信；(subscription_id, event_id) 唯一保证消费端可按 eventId 幂等去重。

create table webhook_subscription (
    id          «uuid» primary key,
    site_id     «uuid» not null,
    name        varchar(100) not null,
    url         varchar(1000) not null,
    secret      varchar(200) not null,
    events      varchar(2000),
    active      «bool» not null default «lit_t»,
    created_at  «ts» not null «def_now»,
    updated_at  «ts» not null
);

create index idx_webhook_subscription_site on webhook_subscription (site_id);

create table webhook_delivery (
    id               «uuid» primary key,
    subscription_id  «uuid» not null,
    event_id         «uuid» not null,
    event_type       varchar(150) not null,
    payload          «txt» not null,
    status           varchar(20) not null,
    attempts         int not null default 0,
    max_attempts     int not null default 5,
    next_attempt_at  «ts»,
    response_status  int,
    last_error       varchar(1000),
    created_at       «ts» not null «def_now»,
    delivered_at     «ts»,
    constraint uq_webhook_delivery_sub_event unique (subscription_id, event_id)
);

create index idx_webhook_delivery_pending on webhook_delivery (status, next_attempt_at);
