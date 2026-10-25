-- FR-AI-MEDIA-002 智能章节（CMS 侧承接）：Knox 字幕轨道时间戳同步入媒体资产字段，
-- 格式为 JSON 数组 [{"time":0,"title":"Introduction"}, ...]，可手动编辑。
-- chapters          章节 JSON 数据
-- chapters_model    产物模型标识（GOV-007 AI 标识；null = 无章节或人工撰写）
-- chapters_synced_at  章节同步时间
ALTER TABLE media_asset
    ADD COLUMN chapters «txt»;
ALTER TABLE media_asset
    ADD COLUMN chapters_model VARCHAR(100);
ALTER TABLE media_asset
    ADD COLUMN chapters_synced_at «ts»;

«cm_V98»


