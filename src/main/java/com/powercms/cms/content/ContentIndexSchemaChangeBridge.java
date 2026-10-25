package com.powercms.cms.content;

import com.powercms.cms.model.ContentTypePublishedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 把 {@code model} 的模型发布事件转译为 {@code content} 域的
 * {@link ContentIndexSchemaChangedEvent}（FR-MODEL-006 / AC-CM-009）。
 *
 * <p><b>存在理由</b>：Spring Modulith 边界为 {@code search → content → model}，
 * {@code search} 不得直接依赖 {@code model}，因此「模型发布」这一 model 事件不能由检索侧
 * 直接监听（{@code ModularityTests} 会判越界）。由本桥接器在 {@code content} 内完成转译，
 * 检索侧只依赖 {@code content}。
 *
 * <p><b>为什么不用 {@code @TransactionalEventListener}</b>：映射比对是旁路诊断，
 * 与发布事务成败无关 —— 即使发布事务回滚，比对结果也不会造成错误状态（只是重算了一次指纹）。
 * 用普通监听器可以让「模型发布成功但比对逻辑异常」不至于把发布事务一起带崩
 * （比对侧另有 try/catch 兜底，两层保险）。
 */
@Component
public class ContentIndexSchemaChangeBridge {

    private final ApplicationEventPublisher publisher;

    public ContentIndexSchemaChangeBridge(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener
    public void onContentTypePublished(ContentTypePublishedEvent event) {
        if (event == null || event.siteId() == null) return;
        publisher.publishEvent(new ContentIndexSchemaChangedEvent(event.siteId()));
    }
}
