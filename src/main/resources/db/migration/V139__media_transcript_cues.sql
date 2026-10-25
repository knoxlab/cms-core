-- V139：转写 cues 时间戳落库（FR-AI-MEDIA-001 扩展 / FR-AI-KB-081 前置）
--
-- 背景与决策（2026-10-02 裁决为「路径 B」）：
-- media_asset.transcript 是**纯文本**（V94：字幕 cues 文本按序拼接，便于编辑与检索），
-- **不含时间戳**。而 FR-AI-KB-081 要求媒体派生文本分块携带 time_start_ms/time_end_ms，
-- FR-AI-KB-082 要求引用可跳转到具体时间点——两者都需要 cues 的时间信息。
--
-- 路径 B（本迁移）：在**已经拉取 cues 的既有同步链路**（KnoxTranscriptSyncService）里
-- 顺带把 cues 落库，而不是等分块时再回读 Knox 对齐。理由：
--   ① 该服务**本来就在拉 cues**（否则无法拼接 transcript），落库是顺手的事，零额外调用；
--   ② 分块因此**完全可离线重算**——与 ADR-16「CMS 必须持有正文」同理，
--      分块策略迭代不必回访 Knox；
--   ③ 避免「transcript 被人工编辑后与回读 cues 失配」这一类需要额外容错的场景。
--
-- 存储格式：JSON 数组 [{"start_ms":0,"end_ms":2500,"text":"..."}]
-- 之所以存毫秒而非原始时间码文本：分块与引用都要按毫秒比较与切分，
-- 每次读取都解析 "00:00:02,500" 既浪费又易错（且 SRT/VTT 格式混用）。
--
-- 方言约束：时间列用 ts/tstz token，大文本用 txt token；**不得**写字面 TIMESTAMPTZ
-- （V111/V113 曾因此导致 H2 下全部 @SpringBootTest 无法启动）。
-- 注释中只可出现**已定义**的占位符 token——Flyway 会在注释里解析占位符，
-- 未定义的键名会让整条迁移解析失败。

alter table media_asset add column transcript_cues «txt»;
-- 与既有 *_synced_at 命名保持一致（transcript_synced_at / summary_synced_at / chapters_synced_at）
alter table media_asset add column transcript_cues_synced_at «ts»;

-- 回填说明：存量资产的 transcript_cues 为 NULL。按 FR-AI-KB-083 的增量语义，
-- 下一次 transcript 同步（transcript-min-interval，默认 12h）会自然补齐；
-- 需要立即回填时由运维触发一次全量转写同步，无需专门的数据迁移脚本。
