-- FR-AI-SRC-003 来源域改写的**可见性**（DEF-MSRC-06 配套）：
-- V128 把存量落点内容与来源事实锚点从 'PIM:<名>' 改写为 'DAM:<名>'，但检索索引里的
-- sourceDomain 是**派生副本**（AiAskService 的跨源引用溯源直接读索引文档字段，
-- 见 AiAskService.toCitations），SQL 迁移碰不到它。
--
-- 后果是「库内已改对、引用仍标 PIM:」：同一批内容在跨源导航（读库）显示 DAM:，
-- 在 AI 引用的跨源溯源（读索引）却仍是 PIM:<DAM 源名>，两条读路径互相矛盾且无任何提示。
-- 域 11 实测：D3 PASS（导航 DAM:）而 E3b FAIL（引用 PIM:），差的就是这份派生副本。
--
-- 按 FR-MODEL-006 既有口径，索引过期不由迁移隐式重建（全量大动作，且要人工确认），
-- 而是**登记基线**让「需重建」可见：把受影响站点的 applied_fingerprint 置空 ——
-- 这正是 SearchIndexMappingStateService 里「从未记录基线」的语义，
-- rebuildRequired 由 !Objects.equals(applied, desired) 判定，置空即真。
-- 运维/管理端随即在映射状态（GET /sites/{id}/search/mapping-state）看到该站点需重建，
-- 重建后由 markApplied 自动把基线对齐回正常（幂等，可重复执行）。
--
-- 作用域只限「确有 DAM 类别数据源」的站点：没有来源域改写的站点不该因此亮起重建提示。
UPDATE search_index_mapping_state
   SET applied_fingerprint = NULL
 WHERE site_id IN (SELECT DISTINCT site_id
                     FROM datasource_registration
                    WHERE source_category = 'DAM');
