package com.powercms.cms.stats;

import java.util.List;

/**
 * 事件类型白名单（FR-STAT-001、NFR-STAT-009）。未在白名单中的类型在采集端被拒绝并计数。
 */
public final class EventTypes {
    public static final String PAGEVIEW = "PAGEVIEW";
    public static final String CONTENT_VIEW = "CONTENT_VIEW";
    public static final String VIDEO_PLAY = "VIDEO_PLAY";
    public static final String VIDEO_HEARTBEAT = "VIDEO_HEARTBEAT";
    public static final String VIDEO_END = "VIDEO_END";
    public static final String DWELL = "DWELL";
    public static final String SEARCH = "SEARCH";
    public static final String ERROR = "ERROR";
    public static final String DOWNLOAD = "DOWNLOAD";
    public static final String CUSTOM = "CUSTOM";

    public static final List<String> ALL = List.of(
        PAGEVIEW, CONTENT_VIEW, VIDEO_PLAY, VIDEO_HEARTBEAT, VIDEO_END,
        DWELL, SEARCH, ERROR, DOWNLOAD, CUSTOM);

    /** 在线判定所依据的活动事件（FR-STAT-066 在线窗口）。 */
    public static final List<String> ACTIVITY = List.of(PAGEVIEW, VIDEO_HEARTBEAT, DWELL);

    private EventTypes() {}

    public static boolean isVideo(String type) {
        return VIDEO_PLAY.equals(type) || VIDEO_HEARTBEAT.equals(type) || VIDEO_END.equals(type);
    }
}
