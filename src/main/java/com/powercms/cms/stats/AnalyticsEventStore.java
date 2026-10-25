package com.powercms.cms.stats;

import com.powercms.cms.stats.entity.AnalyticsEventEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 事件批量写入接口（FR-STAT-102 幂等：event_id 冲突忽略）。
 * 五库（H2/PostgreSQL/MySQL/金仓/达梦）由存储实现按方言分支提供幂等写入语义
 * （ON CONFLICT / insert-select where not exists，见 JpaAnalyticsEventStore）。
 */
public interface AnalyticsEventStore {

    /**
     * 批量幂等插入：以 id（event_id）为唯一键，冲突行跳过，不抛异常。
     *
     * @return 实际新插入的事件列表（与入参顺序一致；重复事件被跳过不返回）。
     *         调用方仅对返回的新事件触发派生副作用（播放会话/在线计数），保证重放安全（FR-STAT-102）。
     */
    List<AnalyticsEventEntity> insertIgnoreBatch(List<AnalyticsEventEntity> events);

    /**
     * 窗口内活跃访客数（去重 visitor_id）与观看中人数（去重，VIDEO_HEARTBEAT）。
     * 用于实时监控在线判定（FR-STAT-066/106）；globalAcrossNodes=true 时不带 server_node 过滤（跨节点去重）。
     */
    OnlineSnapshot onlineSnapshot(UUID siteId, Instant since, String serverNode, boolean globalAcrossNodes);

    record OnlineSnapshot(int onlineCount, int watchingCount) {}
}
