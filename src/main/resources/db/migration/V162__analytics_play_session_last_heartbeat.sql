-- 播放会话心跳活跃时间（NFR-STAT-006 修正）。
--
-- 旧收口条件只看 started_at < now - session_timeout：长视频播放中仍在正常心跳的会话
-- 会在起播约 60~90 秒后被提前标记 TIMEOUT；之后 VIDEO_END 到达时 ended_at 已被占用，
-- 真实结束原因写不进去（完播率/结束原因失真）。实测：100 秒视频 19:26:40 起播、
-- 心跳持续到 19:36，会话却在 19:28:02 被 TIMEOUT 收口。
--
-- 修法：新增 last_heartbeat_at，由心跳原子累加同步刷新
-- （AnalyticsPlaySessionRepository.applyHeartbeat），收口改为「最后一次心跳早于阈值」
-- 才判超时；播放中的会话只要心跳还在到就不收口。
alter table analytics_play_session add column last_heartbeat_at «ts»;

-- 存量行回填：以起播时间兜底，保持升级前的收口语义
-- （升级瞬间仍在播的会话最迟多等一个收口周期再判活）。
update analytics_play_session set last_heartbeat_at = started_at where last_heartbeat_at is null;
