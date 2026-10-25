package com.powercms.cms.content;

import com.powercms.cms.model.ContentTypePublishedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 模块边界的接缝测试：{@code model} 的模型发布事件 → {@code content} 的中转事件。
 *
 * <p>存在的意义不只是「转译对不对」：Spring Modulith 边界为 {@code search → content → model}，
 * 检索侧的映射比对<b>不允许</b>直接监听 {@code model} 的事件（{@code ModularityTests} 会判越界）。
 * 这个桥接器是保持那条边界成立的唯一支点，一旦被删/被绕过，映射生命周期会静默失效
 * —— 那正是「模型改了属性、索引却没提示重建」这类最难排查的故障。故此接缝必须有测试钉住。
 */
class ContentIndexSchemaChangeBridgeTest {

    private static final UUID SITE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TYPE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void modelPublishIsTranslatedIntoSchemaChangedEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        new ContentIndexSchemaChangeBridge(publisher)
            .onContentTypePublished(new ContentTypePublishedEvent(SITE_ID, TYPE_ID, 3));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ContentIndexSchemaChangedEvent.class);
        assertThat(((ContentIndexSchemaChangedEvent) captor.getValue()).siteId()).isEqualTo(SITE_ID);
    }

    /** 站点为空的发布事件（异常/内部调用）不得往下游传，否则下游会按 null 站点查库。 */
    @Test
    void eventWithoutSiteIsDropped() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        new ContentIndexSchemaChangeBridge(publisher)
            .onContentTypePublished(new ContentTypePublishedEvent(null, TYPE_ID, 3));

        verify(publisher, never()).publishEvent(any());
    }
}
