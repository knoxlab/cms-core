-- FIX-13（FR-KNX-022）：对账可追踪转码任务——上传响应携带 taskId 时
-- 存 transcode_task_id，对账按 transcode status 的 poll_after_ms 建议安排下次轮询。
alter table knox_media_ref add column transcode_task_id varchar(100);
