-- FR-AGT-MCP-014：MCP 写工具幂等键记录。
-- 幂等键范围 = actor + tool_name + idempotencyKey；成功结果留档，重放返回既有结果（仅生效一次）。
create table mcp_idempotency (
    id          «uuid» primary key,
    «col_v75_idem»,
    tool_name   varchar(64)  not null,
    actor       varchar(64)  not null,
    site_id     «uuid»,
    result_json «txt»         not null,
    expires_at  «ts»    not null,
    created_at  «ts»    not null «def_now»
);

create index idx_mcp_idem_expires on mcp_idempotency (expires_at);
