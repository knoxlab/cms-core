package com.powercms.cms.search;

import com.powercms.cms.content.PageResourceChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class PageResourceSearchListener {
    private final AdminSearchService search;

    PageResourceSearchListener(AdminSearchService search) { this.search = search; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPageResourceChanged(PageResourceChangedEvent event) {
        search.indexItem(event.pageId());
    }
}
