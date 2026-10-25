-- FR-AI-KB-064：多轮追问的会话标识
--
-- 需求「多轮追问（复用既有会话上下文机制，限 N 轮、限 token）；
-- 追问时不得丢失文档源上下文（如「那它的最大扭矩呢」可继承上一轮命中的文档）」。
--
-- 核实后确认：`AskCommand.sessionId` 早已存在，但**只被用于匿名频控**
-- （传给 `AiQuotaService.tryAcquireAnonymous`），问答链路里**没有任何会话记忆** ——
-- `ai_ask_log` 也没有会话列。因此"继承上一轮命中的文档"无从谈起：
-- 上一轮与这一轮在数据上**完全无关**。
--
-- 这一列是会话记忆的**最小充分载体**：已有 `cited_source_domains` 记录了
-- "这一轮答案实际引用了哪些来源域"，把它按会话串起来就能回答
-- "本会话上一轮用了文档源吗"。因此**不新建会话表** ——
-- 需要的只是"这次属于哪个会话"。
--
-- 可空：既有日志与会话外的单轮提问没有会话标识，这是正常情形，
-- 不是"未配置"。带 `where session_id is not null` 的部分索引支持按会话回溯。
alter table ai_ask_log add column session_id varchar(64);
create index ix_ask_log_session on ai_ask_log (session_id, created_at desc);
