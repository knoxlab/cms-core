package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.repo.*;
import com.powercms.common.error.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @Transactional
public class PageCompositionService {
 private final PageVisualCompositionRepository compositions; private final ContentItemRepository items; private final ContentTypeRepository types; private final SiteTemplateRepository templates; private final SiteDropZoneRepository zones; private final SiteContainerRepository containers; private final SiteContainerStructureRepository containerStructures; private final ComponentNormalizer normalizer; private final ObjectMapper mapper; private final AuditService audit; private final VisualLayoutService layouts;
 PageCompositionService(PageVisualCompositionRepository c,ContentItemRepository i,ContentTypeRepository t,SiteTemplateRepository st,SiteDropZoneRepository z,SiteContainerRepository cr,SiteContainerStructureRepository cs,ComponentNormalizer n,ObjectMapper m,AuditService a,VisualLayoutService l){compositions=c;items=i;types=t;templates=st;zones=z;containers=cr;containerStructures=cs;normalizer=n;mapper=m;audit=a;layouts=l;}
 public record CompositionInfo(UUID id,UUID pageId,UUID templateId,Map<String,List<ComponentInstance>> zones,int version,String updatedBy,Instant updatedAt){}
 @Transactional(readOnly=true) public CompositionInfo get(UUID siteId,UUID pageId){requirePage(siteId,pageId);return compositions.findByPageId(pageId).map(this::info).orElse(new CompositionInfo(null,pageId,null,Map.of(),0,null,null));}
 /** 系统组件类型（如统计采集）无视容器 allowlist 限制，可投放任意页面；每次 save 仅查一次库。 */
 private Set<UUID> systemComponentTypeIds(UUID siteId){return types.findBySiteIdAndObjectTypeOrderByCodeAsc(siteId,"COMPONENT").stream().filter(ContentTypeEntity::isSystem).map(ContentTypeEntity::getId).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));}
 public CompositionInfo save(UUID siteId,UUID pageId,UUID templateId,Map<String,List<ComponentInstance>> document,int expectedVersion,String actor){
  requirePage(siteId,pageId);var template=templates.findById(templateId).filter(t->siteId.equals(t.getSiteId())).orElseThrow(()->bad("页面尚未绑定可视化模板"));
  var existing=compositions.findByPageId(pageId).orElse(null);int actual=existing==null?0:existing.getVersion();if(expectedVersion!=actual)throw new BusinessException(ErrorCodes.CONTENT_VERSION_CONFLICT,"页面组合已被他人修改，请刷新后重试，期望版本 "+expectedVersion+"，当前版本 "+actual);
  var definitions=zones.findByTemplateIdOrderBySortOrderAsc(templateId);Map<String,com.powercms.cms.sitepackage.entity.SiteDropZoneEntity> byKey=new LinkedHashMap<>();definitions.forEach(z->byKey.put(z.getZoneKey(),z));
  Map<String,List<ComponentInstance>> normalized=new LinkedHashMap<>();Set<UUID> systemTypes=null;
  if(document!=null)for(var entry:document.entrySet()){
   var zone=byKey.get(entry.getKey());if(zone==null)throw bad("投放区不存在: "+entry.getKey());Set<UUID> allowed=allowed(zone.getContainerId());int max=maxContents(zone.getContainerId());List<ComponentInstance> inputs=entry.getValue()==null?List.of():entry.getValue();if(max>0&&inputs.size()>max)throw bad("投放区组件数量超过上限 "+max);List<ComponentInstance> list=new ArrayList<>();Set<String> ids=new HashSet<>();int order=0;
   for(var input:inputs){var component=normalizer.normalize(siteId,input,order++);if(!ids.add(component.id()))throw bad("组件 id 重复: "+component.id());UUID typeId=component.componentTypeId();if(component.kind()==ComponentInstance.Kind.SHARED)typeId=null; // shared type is checked when rendering
    if(typeId!=null&&!allowed.isEmpty()&&!allowed.contains(typeId)){if(systemTypes==null)systemTypes=systemComponentTypeIds(siteId);if(!systemTypes.contains(typeId))throw bad("组件类型不在投放区允许范围内: "+entry.getKey()+" type="+typeId+" allowed="+allowed);}list.add(component);}
   normalized.put(entry.getKey(),List.copyOf(list));
  }
  var entity=existing==null?new PageVisualCompositionEntity():existing;entity.setPageId(pageId);entity.setTemplateId(template.getId());entity.setZonesJson(write(normalized));entity.setVersion(actual+1);entity.setUpdatedBy(actor);entity=compositions.save(entity);audit.write(actor,"PAGE_COMPOSITION_SAVE","PAGE_COMPOSITION",entity.getId().toString(),siteId,"SUCCESS","version="+entity.getVersion());return info(entity);
 }
 public CompositionInfo savePageLayout(UUID siteId,UUID pageId,String layoutJson,int expectedVersion,String actor){
  requirePage(siteId,pageId);
  var existing=compositions.findByPageId(pageId).orElse(null);
  if(existing==null||existing.getTemplateId()==null)throw bad("页面尚未绑定可视化模板");
  int actual=existing.getVersion();
  if(expectedVersion!=actual)throw new BusinessException(ErrorCodes.CONTENT_VERSION_CONFLICT,"页面布局已被他人修改，请刷新后重试");
  var updated=layouts.savePageLayout(siteId,existing.getTemplateId(),layoutJson,actor,"更新页面布局");
  if(!updated.id().equals(existing.getTemplateId())){
   UUID oldTemplateId=existing.getTemplateId();
   existing.setTemplateId(updated.id());
   existing.setVersion(actual+1);
   existing.setUpdatedBy(actor);
   compositions.save(existing);
   audit.write(actor,"PAGE_LAYOUT_DETACH","PAGE_COMPOSITION",existing.getId().toString(),siteId,"SUCCESS",
     "template "+oldTemplateId+" -> anonymous "+updated.id());
  }
  return info(existing);
 }
 /** 解绑：删除页面组合绑定，渲染回到「无组合 → .ftl 直出」分支。无绑定时按 expectedVersion=0 幂等返回。 */
 public CompositionInfo unbind(UUID siteId,UUID pageId,int expectedVersion,String actor){
  requirePage(siteId,pageId);
  var existing=compositions.findByPageId(pageId).orElse(null);int actual=existing==null?0:existing.getVersion();
  if(expectedVersion!=actual)throw new BusinessException(ErrorCodes.CONTENT_VERSION_CONFLICT,"页面组合已被他人修改，请刷新后重试，期望版本 "+expectedVersion+"，当前版本 "+actual);
  if(existing!=null){
   UUID templateId=existing.getTemplateId();UUID id=existing.getId();
   compositions.delete(existing);
   audit.write(actor,"PAGE_COMPOSITION_UNBIND","PAGE_COMPOSITION",id.toString(),siteId,"SUCCESS","template="+templateId+" version="+actual);
  }
  return new CompositionInfo(null,pageId,null,Map.of(),0,null,null);
 }
 public CompositionInfo add(UUID siteId,UUID pageId,String zoneKey,ComponentInstance component,int expected,String actor){var c=get(siteId,pageId);if(c.templateId()==null)throw bad("页面尚未绑定可视化模板");var d=mutable(c.zones());d.computeIfAbsent(zoneKey,k->new ArrayList<>()).add(component);return save(siteId,pageId,c.templateId(),d,expected,actor);}
 public CompositionInfo update(UUID siteId,UUID pageId,String instanceId,ComponentInstance value,int expected,String actor){var c=get(siteId,pageId);var d=mutable(c.zones());boolean found=false;for(var list:d.values())for(int i=0;i<list.size();i++)if(list.get(i).id().equals(instanceId)){list.set(i,value);found=true;}if(!found)throw missing("组件实例不存在");return save(siteId,pageId,c.templateId(),d,expected,actor);}
 public CompositionInfo delete(UUID siteId,UUID pageId,String instanceId,int expected,String actor){var c=get(siteId,pageId);var d=mutable(c.zones());boolean found=d.values().stream().anyMatch(list->list.removeIf(x->x.id().equals(instanceId)));if(!found)throw missing("组件实例不存在");return save(siteId,pageId,c.templateId(),d,expected,actor);}
 public CompositionInfo move(UUID siteId,UUID pageId,String instanceId,String targetZone,int targetIndex,int expected,String actor){var c=get(siteId,pageId);var d=mutable(c.zones());ComponentInstance found=null;for(var list:d.values()){for(var x:list)if(x.id().equals(instanceId)){found=x;break;}if(found!=null){list.remove(found);break;}}if(found==null)throw missing("组件实例不存在");var target=d.computeIfAbsent(targetZone,k->new ArrayList<>());target.add(Math.max(0,Math.min(targetIndex,target.size())),found);return save(siteId,pageId,c.templateId(),d,expected,actor);}
 @Transactional(readOnly=true) public Map<String,List<ComponentInstance>> zones(UUID pageId){return compositions.findByPageId(pageId).map(this::parse).orElse(Map.of());}
 private Map<String,List<ComponentInstance>> mutable(Map<String,List<ComponentInstance>> src){var out=new LinkedHashMap<String,List<ComponentInstance>>();src.forEach((k,v)->out.put(k,new ArrayList<>(v)));return out;}
 private com.powercms.cms.content.entity.ContentItemEntity requirePage(UUID siteId,UUID pageId){var p=items.findById(pageId).filter(x->siteId.equals(x.getSiteId())).orElseThrow(()->missing("页面不存在"));var t=types.findById(p.getContentTypeId()).orElseThrow(()->bad("内容模型不存在"));if(!"PAGE".equalsIgnoreCase(t.getObjectType()))throw bad("只有 PAGE 类型可以作为页面渲染");return p;}
 private Set<UUID> allowed(UUID id){if(id==null)return Set.of();var c=containers.findById(id).orElseThrow(()->bad("容器不存在"));var links=containerStructures.findByContainerIdOrderBySortOrderAsc(id);if(!links.isEmpty())return links.stream().map(link->link.getStructureId()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));try{return mapper.readValue(c.getAllowedComponentTypes(),new TypeReference<LinkedHashSet<UUID>>(){});}catch(Exception e){throw bad("容器允许组件类型配置不是合法 JSON");}}
 private int maxContents(UUID id){if(id==null)return 0;var c=containers.findById(id).orElseThrow(()->bad("容器不存在"));try{return mapper.readTree(c.getConfig()).path("maxContents").asInt(0);}catch(Exception e){throw bad("容器配置不是合法 JSON");}}
 private CompositionInfo info(PageVisualCompositionEntity e){return new CompositionInfo(e.getId(),e.getPageId(),e.getTemplateId(),parse(e),e.getVersion(),e.getUpdatedBy(),e.getUpdatedAt());}
 private Map<String,List<ComponentInstance>> parse(PageVisualCompositionEntity e){try{return mapper.readValue(e.getZonesJson(),new TypeReference<LinkedHashMap<String,List<ComponentInstance>>>(){});}catch(Exception ex){throw bad("页面组合数据不是合法 JSON");}}
 private String write(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw bad("页面组合数据序列化失败");}}
 private BusinessException bad(String m){return new BusinessException(ErrorCodes.VALIDATION_FAILED,m);}private BusinessException missing(String m){return new BusinessException(ErrorCodes.NOT_FOUND,m);}
}
