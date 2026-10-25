package com.powercms.cms.content;

import java.util.UUID;

/**
 * 内容模型的索引形态可能已变化（FR-MODEL-006 / AC-CM-009）。
 *
 * <p><b>为什么需要这个中转事件</b>：模型发布事件 {@code ContentTypePublishedEvent} 定义在
 * {@code model} 模块，而 Spring Modulith 边界是 {@code search → content → model} ——
 * {@code search} 不允许直接依赖 {@code model}。若让检索侧直接监听 model 的事件，
 * {@code ModularityTests} 会判定越界。
 *
 * <p>因此由 {@link ContentIndexSchemaChangeBridge} 在 {@code content} 模块内接住模型发布事件，
 * 转译成本事件（{@code search → content} 是允许的方向）。事件只携带站点：具体「哪些字段级键
 * 形态变了」由消费方按站点重新派生 schema 后自行比对 —— 事件本身不做判断，避免把
 * 「模型差异」这个易变的语义固化在事件里。
 *
 * @param siteId 受影响的站点
 */
public record ContentIndexSchemaChangedEvent(UUID siteId) {}
