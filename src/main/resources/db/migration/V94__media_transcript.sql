-- FR-AI-MEDIA-001 视频自动转写（CMS 侧承接）：转写稿回写 MEDIA 资产结构化字段，可编辑、可检索。
-- 分工边界（规划 §6.5）：转写在 Knox-Media（本地 Whisper 为主）完成并产出字幕轨道；
-- PowerCMS 通过字幕 API 拉取 cues 拼接为转写稿文本，仅做结构化字段回写，
-- 转写向量索引保留在 Knox 侧，两套向量索引不合并、不互拷。
-- transcript       转写稿全文（字幕 cues 文本按序拼接，纯文本便于编辑与语义检索）
-- transcript_lang  转写语种（BCP-47 主子标签，如 zh/en；取自字幕轨道 lang）
-- transcript_model 产物模型标识（GOV-007 AI 标识：如 knox-whisper；回写内容项 ai_model）
-- transcript_synced_at 增量同步游标（KnoxTranscriptSyncService；null = 从未同步）
ALTER TABLE media_asset
    ADD COLUMN transcript «txt»;
ALTER TABLE media_asset
    ADD COLUMN transcript_lang VARCHAR(20);
ALTER TABLE media_asset
    ADD COLUMN transcript_model VARCHAR(100);
ALTER TABLE media_asset
    ADD COLUMN transcript_synced_at «ts»;

«cm_V94»



