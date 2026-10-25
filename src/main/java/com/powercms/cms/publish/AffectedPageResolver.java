package com.powercms.cms.publish;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.ComponentInstance;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves which published pages are affected by a content item change.
 * Build-time page digest diffs remain authoritative; this supports event-driven incremental requests.
 */
@Service
public class AffectedPageResolver {
    private final ContentItemRepository items;
    private final ContentTypeRepository types;
    private final PageVisualCompositionRepository compositions;
    private final ObjectMapper mapper;

    AffectedPageResolver(ContentItemRepository items, ContentTypeRepository types,
                         PageVisualCompositionRepository compositions, ObjectMapper mapper) {
        this.items = items;
        this.types = types;
        this.compositions = compositions;
        this.mapper = mapper;
    }

    public record AffectedPage(UUID pageId, String routePath) {}

    @Transactional(readOnly = true)
    public List<AffectedPage> resolve(UUID siteId, UUID contentItemId) {
        ContentItemEntity item = items.findById(contentItemId).orElse(null);
        if (item == null || !siteId.equals(item.getSiteId())) return List.of();
        Set<UUID> pageIds = new LinkedHashSet<>();
        ContentTypeEntity type = types.findById(item.getContentTypeId()).orElse(null);
        if (type != null && "PAGE".equalsIgnoreCase(type.getObjectType())
            && item.getRoutePath() != null && !item.getRoutePath().isBlank()) {
            pageIds.add(item.getId());
        }
        for (PageVisualCompositionEntity composition : compositions.findAll()) {
            if (referencesContent(composition.getZonesJson(), contentItemId)) {
                pageIds.add(composition.getPageId());
            }
        }
        List<AffectedPage> out = new ArrayList<>();
        for (UUID pageId : pageIds) {
            ContentItemEntity page = items.findById(pageId).orElse(null);
            if (page == null || !siteId.equals(page.getSiteId())) continue;
            if (page.getRoutePath() == null || page.getRoutePath().isBlank()) continue;
            out.add(new AffectedPage(pageId, page.getRoutePath()));
        }
        return List.copyOf(out);
    }

    boolean referencesContent(String zonesJson, UUID contentItemId) {
        if (zonesJson == null || zonesJson.isBlank() || contentItemId == null) return false;
        try {
            Map<String, List<ComponentInstance>> zones = mapper.readValue(zonesJson,
                new TypeReference<LinkedHashMap<String, List<ComponentInstance>>>() {});
            for (List<ComponentInstance> list : zones.values()) {
                if (list == null) continue;
                for (ComponentInstance component : list) {
                    if (component == null) continue;
                    if (component.kind() == ComponentInstance.Kind.CONTENT
                        && contentItemId.equals(component.contentItemId())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception ex) {
            return zonesJson.contains(contentItemId.toString());
        }
    }
}
