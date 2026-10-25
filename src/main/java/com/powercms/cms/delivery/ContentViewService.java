package com.powercms.cms.delivery;

import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentViewService {
  private final ContentItemRepository items;

  ContentViewService(ContentItemRepository items) {
    this.items = items;
  }

  @Transactional
  public long recordView(UUID siteId, UUID contentId) {
    var item =
        items
            .findByIdAndSiteIdForUpdate(contentId, siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
    long next = item.getViewCount() + 1L;
    item.setViewCount(next);
    items.save(item);
    return next;
  }
}
