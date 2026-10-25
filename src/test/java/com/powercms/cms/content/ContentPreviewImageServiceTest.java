package com.powercms.cms.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPreviewImageServiceTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();

    @Mock private ContentVersionRepository versionRepo;
    @Mock private FieldDefinitionRepository fieldRepo;

    private ContentPreviewImageService service;

    @BeforeEach
    void setUp() {
        service = new ContentPreviewImageService(versionRepo, fieldRepo, new ObjectMapper());
    }

    @Test
    void usesFirstNonBlankImageFieldInDesignerOrder() {
        ContentItemEntity item = item();
        when(fieldRepo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any()))
            .thenReturn(List.of(imageField("cover_s", 1), imageField("photo_s", 2)));
        when(versionRepo.findLatestByContentItemIdIn(any()))
            .thenReturn(List.of(version(item.getId(), "{\"cover_s\":\"\",\"photo_s\":\"asset-photo\"}")));

        assertThat(service.resolve(List.of(item)))
            .containsEntry(item.getId(), "asset-photo");
    }

    @Test
    void thumbnailSystemFieldWinsOverUserImageFields() {
        ContentItemEntity item = item();
        FieldDefinitionEntity thumbnail = imageField("thumbnail_url_s", 1);
        thumbnail.setSystemFieldType(ContentPreviewImageService.THUMBNAIL_SYSTEM_FIELD);
        when(fieldRepo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any()))
            .thenReturn(List.of(imageField("cover_s", 2), thumbnail));
        when(versionRepo.findLatestByContentItemIdIn(any()))
            .thenReturn(List.of(version(item.getId(), "{\"cover_s\":\"asset-cover\",\"thumbnail_url_s\":\"asset-thumb\"}")));

        assertThat(service.resolve(List.of(item)))
            .containsEntry(item.getId(), "asset-thumb");
    }

    @Test
    void acceptsMediaRefJsonAndSkipsEntriesWithoutPreview() {
        ContentItemEntity withRef = item();
        ContentItemEntity withoutImages = item();
        FieldDefinitionEntity cover = imageField("cover_s", 1);
        when(fieldRepo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any()))
            .thenReturn(List.of(cover));
        when(versionRepo.findLatestByContentItemIdIn(any()))
            .thenReturn(List.of(
                version(withRef.getId(), "{\"cover_s\":{\"assetId\":\"asset-ref\"}}"),
                version(withoutImages.getId(), "{\"title_s\":\"无图\"}")));

        Map<UUID, String> previews = service.resolve(List.of(withRef, withoutImages));

        assertThat(previews).containsEntry(withRef.getId(), "asset-ref");
        assertThat(previews).doesNotContainKey(withoutImages.getId());
    }

    @Test
    void returnsEmptyWhenContentTypeHasNoImageField() {
        ContentItemEntity item = item();
        when(fieldRepo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any()))
            .thenReturn(List.of(textField("title_s")));

        assertThat(service.resolve(List.of(item))).isEmpty();
    }

    private ContentItemEntity item() {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(UUID.randomUUID());
        item.setContentTypeId(TYPE);
        return item;
    }

    private ContentVersionEntity version(UUID itemId, String fieldValues) {
        return ContentVersionEntity.create(itemId, 1, fieldValues, 1, "tester", "test");
    }

    private FieldDefinitionEntity imageField(String code, int sortOrder) {
        FieldDefinitionEntity field = new FieldDefinitionEntity();
        field.setId(UUID.randomUUID());
        field.setContentTypeId(TYPE);
        field.setCode(code);
        field.setName(code);
        field.setFieldType("IMAGE");
        field.setSortOrder(sortOrder);
        return field;
    }

    private FieldDefinitionEntity textField(String code) {
        FieldDefinitionEntity field = new FieldDefinitionEntity();
        field.setId(UUID.randomUUID());
        field.setContentTypeId(TYPE);
        field.setCode(code);
        field.setName(code);
        field.setFieldType("TEXT");
        return field;
    }
}
