package com.powercms.cms.identity.permission;

import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 可授权对象的父级解析（spec 4.7 继承链）。
 *
 * <pre>
 * CONTENT → FOLDER（有栏目）或 CONTENT_TYPE → SITE → ORG
 * FOLDER  → FOLDER（父栏目）或 SITE
 * TAXONOMY→ TAXONOMY（父节点）或 SITE
 * SITE    → ORG
 * </pre>
 */
@Component
public class PermissionableResolver {

    /** 对象引用：类型 + id。 */
    public record Ref(String objectType, UUID objectId) {}

    private final ContentItemRepository contents;
    private final SiteFolderRepository folders;
    private final ContentTypeRepository contentTypes;
    private final CategoryRepository categories;
    private final SiteRepository sites;
    private final MediaAssetRepository media;

    public PermissionableResolver(ContentItemRepository contents, SiteFolderRepository folders,
                                  ContentTypeRepository contentTypes, CategoryRepository categories,
                                  SiteRepository sites, MediaAssetRepository media) {
        this.contents = contents;
        this.folders = folders;
        this.contentTypes = contentTypes;
        this.categories = categories;
        this.sites = sites;
        this.media = media;
    }

    /** 返回父级引用；ORG 返回 null。 */
    public Ref parent(String objectType, UUID objectId) {
        if (objectType == null || objectId == null) return null;
        return switch (objectType) {
            case ObjectType.CONTENT -> parentOfContent(objectId);
            case ObjectType.FOLDER -> parentOfFolder(objectId);
            case ObjectType.TAXONOMY -> parentOfTaxonomy(objectId);
            case ObjectType.CONTENT_TYPE -> parentOfContentType(objectId);
            case ObjectType.MEDIA -> parentOfMedia(objectId);
            case ObjectType.TEMPLATE, ObjectType.CONTAINER, ObjectType.WORKFLOW, ObjectType.SITE ->
                parentOfSite(objectId);
            case ObjectType.ORG -> null;
            default -> null;
        };
    }

    private Ref parentOfContent(UUID id) {
        return contents.findById(id).flatMap(item -> {
            if (item.getFolderId() != null) {
                return java.util.Optional.of(new Ref(ObjectType.FOLDER, item.getFolderId()));
            }
            return java.util.Optional.of(new Ref(ObjectType.CONTENT_TYPE, item.getContentTypeId()));
        }).orElse(null);
    }

    private Ref parentOfFolder(UUID id) {
        return folders.findById(id).flatMap(folder -> {
            if (folder.getParentId() != null) {
                return java.util.Optional.of(new Ref(ObjectType.FOLDER, folder.getParentId()));
            }
            return java.util.Optional.of(new Ref(ObjectType.SITE, folder.getSiteId()));
        }).orElse(null);
    }

    private Ref parentOfTaxonomy(UUID id) {
        return categories.findById(id).flatMap(category -> {
            if (category.getParentId() != null) {
                return java.util.Optional.of(new Ref(ObjectType.TAXONOMY, category.getParentId()));
            }
            return java.util.Optional.of(new Ref(ObjectType.SITE, category.getSiteId()));
        }).orElse(null);
    }

    private Ref parentOfContentType(UUID id) {
        return contentTypes.findById(id)
            .map(ct -> new Ref(ObjectType.SITE, ct.getSiteId())).orElse(null);
    }

    private Ref parentOfMedia(UUID id) {
        // 媒体资产挂内容项（MEDIA 内容类型）时以其内容项为父；无则退化到站点。
        return media.findById(id)
            .flatMap(asset -> {
                if (asset.getContentItemId() != null) {
                    return java.util.Optional.of(new Ref(ObjectType.CONTENT, asset.getContentItemId()));
                }
                return java.util.Optional.of(new Ref(ObjectType.SITE, asset.getSiteId()));
            })
            .orElse(null);
    }

    private Ref parentOfSite(UUID id) {
        return sites.findById(id)
            .map(site -> new Ref(ObjectType.ORG, site.getOrganizationId())).orElse(null);
    }
}
