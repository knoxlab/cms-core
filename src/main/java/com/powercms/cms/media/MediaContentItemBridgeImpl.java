package com.powercms.cms.media;

import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.entity.MediaVersionEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaVersionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaContentItemBridgeImpl implements MediaContentItemBridge {

    private final MediaAssetRepository assetRepo;
    private final MediaVersionRepository versionRepo;

    public MediaContentItemBridgeImpl(MediaAssetRepository assetRepo, MediaVersionRepository versionRepo) {
        this.assetRepo = assetRepo;
        this.versionRepo = versionRepo;
    }

    @Override
    public Optional<AssetDetail> findById(UUID assetId) {
        return assetRepo.findById(assetId).map(this::detail);
    }

    @Override
    public List<AssetDetail> findAllClassifiedWithoutContentItem() {
        return assetRepo.findByMediaTypeIdNotNullAndContentItemIdIsNull().stream().map(this::detail).toList();
    }

    @Override
    public void linkContentItem(UUID assetId, UUID contentItemId) {
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setContentItemId(contentItemId);
            assetRepo.save(a);
        });
    }

    private AssetDetail detail(MediaAssetEntity a) {
        String storageKey = versionRepo.findByMediaAssetIdAndVersion(a.getId(), a.getCurrentVersion())
            .map(MediaVersionEntity::getStorageKey)
            .orElse(null);
        return new AssetDetail(a.getId(), a.getSiteId(), a.getTitle(), a.getFilename(), a.getMimeType(),
            a.getFileSize(), a.getContentHash(), storageKey, a.getStatus(), a.getKind(),
            a.getMediaTypeId(), a.getMetadata(), a.getContentItemId(),
            a.getTranscript(), a.getTranscriptLang(), a.getTranscriptModel());
    }
}
