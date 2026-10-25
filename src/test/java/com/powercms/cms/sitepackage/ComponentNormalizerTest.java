package com.powercms.cms.sitepackage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.sitepackage.repo.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ComponentNormalizerTest {
 private final ContentTypeRepository types=mock(ContentTypeRepository.class);
 private final SharedComponentRepository shared=mock(SharedComponentRepository.class);
 private final SiteContainerRepository containers=mock(SiteContainerRepository.class);
 private final ContentItemRepository items=mock(ContentItemRepository.class);
 private final ComponentNormalizer normalizer=new ComponentNormalizer(new ObjectMapper(),types,shared,containers,null,items);
 @Test void normalizesEmbedded(){UUID site=UUID.randomUUID(),typeId=UUID.randomUUID();var type=new ContentTypeEntity();type.setSiteId(site);type.setObjectType("COMPONENT");when(types.findById(typeId)).thenReturn(Optional.of(type));var value=normalizer.normalize(site,new ComponentInstance(UUID.randomUUID().toString(),ComponentInstance.Kind.EMBEDDED,null,null,typeId,"Card",Map.of("x",1),"templates/card.ftl",null,Map.of("theme","dark"),99),0);assertThat(value.order()).isZero();assertThat(value.data()).containsEntry("x",1);}
 @Test void rejectsMalformedIdAndCrossSiteShared(){UUID site=UUID.randomUUID(),id=UUID.randomUUID();assertThatThrownBy(()->normalizer.normalize(site,new ComponentInstance("bad",ComponentInstance.Kind.EMBEDDED,null,null,id,"",Map.of(),null,null,Map.of(),0),0)).hasMessageContaining("UUID");var e=new com.powercms.cms.sitepackage.entity.SharedComponentEntity();e.setSiteId(UUID.randomUUID());when(shared.findById(id)).thenReturn(Optional.of(e));assertThatThrownBy(()->normalizer.normalize(site,new ComponentInstance(UUID.randomUUID().toString(),ComponentInstance.Kind.SHARED,id,null,null,"",Map.of(),null,null,Map.of(),0),0)).hasMessageContaining("??");}
 @Test void rejectsUnsafeTemplateAndOversizedJson(){UUID site=UUID.randomUUID(),typeId=UUID.randomUUID();var type=new ContentTypeEntity();type.setSiteId(site);type.setObjectType("COMPONENT");when(types.findById(typeId)).thenReturn(Optional.of(type));assertThatThrownBy(()->normalizer.normalize(site,new ComponentInstance(UUID.randomUUID().toString(),ComponentInstance.Kind.EMBEDDED,null,null,typeId,"",Map.of(),"../secret.ftl",null,Map.of(),0),0)).hasMessageContaining("templates");assertThatThrownBy(()->normalizer.normalize(site,new ComponentInstance(UUID.randomUUID().toString(),ComponentInstance.Kind.EMBEDDED,null,null,typeId,"",Map.of("blob","x".repeat(300000)),null,null,Map.of(),0),0)).hasMessageContaining("256 KiB");}
 @Test void normalizesPersistedContentReference(){UUID site=UUID.randomUUID(),typeId=UUID.randomUUID(),itemId=UUID.randomUUID();var type=new ContentTypeEntity();type.setSiteId(site);type.setObjectType("CONTENT");var item=new ContentItemEntity();item.setId(itemId);item.setSiteId(site);item.setContentTypeId(typeId);item.setTitle("Independent card");item.setStatus("PUBLISHED");when(items.findById(itemId)).thenReturn(Optional.of(item));when(types.findById(typeId)).thenReturn(Optional.of(type));var input=new ComponentInstance(UUID.randomUUID().toString(),ComponentInstance.Kind.CONTENT,null,null,null,null,Map.of("snapshot","must be ignored"),null,null,Map.of(),12,null,Map.of(),itemId,null);var value=normalizer.normalize(site,input,3);assertThat(value.kind()).isEqualTo(ComponentInstance.Kind.CONTENT);assertThat(value.contentItemId()).isEqualTo(itemId);assertThat(value.componentTypeId()).isEqualTo(typeId);assertThat(value.data()).isEmpty();assertThat(value.order()).isEqualTo(3);}
}
