package com.powercms.cms.content.relation;

import java.util.UUID;

/**
 * 内容关系实例/定义变更事件（DEF-AI-07）。
 *
 * <p>关系服务只负责关系本身的读写，图谱投影由 {@code kg} 模块监听本事件完成——
 * 这样既保持「关系变更即时反映到图」，又不引入 content → kg 的模块依赖
 * （与 {@code ContentChangedEvent} 同一模式）。
 *
 * @param siteId            站点
 * @param relationId        关系定义 id
 * @param definitionRemoved 为 {@code true} 时表示关系定义已删除，需清理该关系名下全部边
 */
public record ContentRelationChangedEvent(UUID siteId, UUID relationId, boolean definitionRemoved) {}
