-- FR-AI-SRC-002：Knox DAM 资产权属元数据同步增量游标。
-- rights_synced_at：上次成功从 Knox meta_json.powercms 块同步版权/许可/有效期的时间；
-- null = 从未同步（全量首轮）。同步失败不推进游标，下轮重试。
ALTER TABLE knox_media_ref
    ADD COLUMN rights_synced_at «tstz»;

create «idx» idx_knox_ref_rights_sync
    ON knox_media_ref (rights_synced_at);
