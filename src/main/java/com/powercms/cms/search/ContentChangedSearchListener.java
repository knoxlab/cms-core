package com.powercms.cms.search;

import com.powercms.cms.content.ContentChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class ContentChangedSearchListener {
    private final AdminSearchService search;

    ContentChangedSearchListener(AdminSearchService search) { this.search = search; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentChanged(ContentChangedEvent event) {
        search.indexItem(event.contentItemId());
    }
}
