-- FR-AI-MEDIA-002 视频内容摘要（CMS 侧承接）：LLM 基于转写稿生成的摘要入资产字段，可编辑、可检索。
-- summary        摘要正文（80~200 字，一段成文）
-- summary_model  产物模型标识（GOV-007 AI 标识；null = 无摘要或人工撰写）
-- summary_synced_at  摘要生成时间
ALTER TABLE media_asset
    ADD COLUMN summary «txt»;
ALTER TABLE media_asset
    ADD COLUMN summary_model VARCHAR(100);
ALTER TABLE media_asset
    ADD COLUMN summary_synced_at «ts»;

«cm_V97»


