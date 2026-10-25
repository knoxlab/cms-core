package com.powercms.cms.audit;

import com.powercms.cms.audit.entity.AuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 访问拒绝审计：页面、播放、下载被拒。action 为空时返回这三类。 */
@Service
public class AccessDenialQuery {

    public static final Set<String> ACTIONS = Set.of(
        "PAGE_ACCESS_DENIED", "MEDIA_PLAY_DENIED", "MEDIA_DOWNLOAD_DENIED");

    private static final int MAX_SCAN_PAGES = 20;
    private static final int SCAN_PAGE_SIZE = 200;

    public record Item(UUID id, Instant occurredAt, String actor, String action,
                       String objectType, String objectId, String result, String detail) {}

    public record AuditPage(List<Item> items, long total, int page, int size) {}

    public AuditPage query(Function<Pageable, Page<AuditEventEntity>> source, String action, int page, int size) {
        String wanted = action == null || action.isBlank() ? null : action.trim();
        if (wanted != null && !ACTIONS.contains(wanted)) {
            return new AuditPage(List.of(), 0, Math.max(0, page), Math.max(1, Math.min(size, 100)));
        }
        int pageNo = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 100));
        List<AuditEventEntity> matched = new ArrayList<>();
        int scanPage = 0;
        while (matched.size() < (long) pageSize * (pageNo + 1) && scanPage < MAX_SCAN_PAGES) {
            Page<AuditEventEntity> events = source.apply(PageRequest.of(scanPage, SCAN_PAGE_SIZE));
            for (AuditEventEntity event : events.getContent()) {
                if (event.getAction() == null || !ACTIONS.contains(event.getAction())) continue;
                if (wanted != null && !wanted.equals(event.getAction())) continue;
                matched.add(event);
            }
            if (events.getContent().size() < SCAN_PAGE_SIZE) break;
            scanPage++;
        }
        List<Item> items = matched.stream()
            .skip((long) pageNo * pageSize)
            .limit(pageSize)
            .map(AccessDenialQuery::toItem)
            .toList();
        return new AuditPage(items, matched.size(), pageNo, pageSize);
    }

    private static Item toItem(AuditEventEntity event) {
        return new Item(event.getId(), event.getOccurredAt(), event.getActor(), event.getAction(),
            event.getObjectType(), event.getObjectId(), event.getResult(), event.getDetail());
    }
}
