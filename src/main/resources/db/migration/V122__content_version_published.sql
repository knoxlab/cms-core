-- DEF-11-02：把「发布」作为一次版本事件落库，版本历史可见发布节点。
--
-- 背景：发布此前只改 content_item.status / published_version，不写 content_version，
-- 版本历史里查不到「哪一版发布了什么」；发布留痕只散落在 outbox 事件与发布队列中，
-- 无法从内容自身的版本链回答「线上跑的是哪一版、那一版的内容是什么」。
--
-- 口径（产品确认方案 B）：发布成功且「发布事件成立」（首次发布，或自上次发布后内容又有变更）时，
-- 以条目当前内容快照新增一条版本（note=发布）并置 published = TRUE，content_item.published_version
-- 指向该版本；内容未变的重复发布不新增版本，避免版本历史被空发布刷屏。
--
-- 回填：存量数据按「content_item.published_version = content_version.version」标记发布版本，
-- 让既有内容的版本历史立即可见发布节点（只能回填当前指向的那一版，历史多次发布无法还原）。
ALTER TABLE content_version
    ADD COLUMN published «bool» NOT NULL DEFAULT «lit_f»;

UPDATE content_version v
   SET published = «lit_t»
 WHERE EXISTS (
     SELECT 1
       FROM content_item i
      WHERE i.id = v.content_item_id
        AND i.published_version = v.version
 );

«cm_V122»
