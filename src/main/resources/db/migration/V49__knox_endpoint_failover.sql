-- FIX-12（FR-KNX-002）：健康检查连续失败自动降级——端点记录连续失败次数，
-- 达到阈值后 health_state=DOWN，上传路由据此自动切换回退端点。
alter table knox_endpoint add column consecutive_failures int not null default 0;
