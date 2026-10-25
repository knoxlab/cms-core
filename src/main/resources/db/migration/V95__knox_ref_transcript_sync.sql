-- FR-AI-MEDIA-001 转写同步游标：knox_media_ref.transcript_synced_at（与 rights_synced_at 同语义）。
-- null = 从未同步（首轮全量）；已同步行按 transcript-min-interval（默认 12h）低频复查上游字幕变更。
ALTER TABLE knox_media_ref
    ADD COLUMN transcript_synced_at «ts»;

«cm_V95»
