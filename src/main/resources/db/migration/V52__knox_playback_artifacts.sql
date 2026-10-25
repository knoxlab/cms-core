-- FR-KNX-023 处理产物落库：播放计划/档位/播放代码/雪碧图/VTT 快照存 knox_media_ref，
-- media_version.artifacts_json 随版本版本化（替换文件出新版本时旧版本产物保留供历史 Release 引用）。
alter table knox_media_ref add column playback_json «txt»;
alter table media_version add column artifacts_json «txt»;
