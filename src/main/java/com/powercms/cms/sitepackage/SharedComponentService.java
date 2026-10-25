package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.entity.*;
import com.powercms.cms.sitepackage.repo.*;
import com.powercms.common.error.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @Transactional
public class SharedComponentService {
 private final com.powercms.cms.content.repo.ContentItemRepository items; private final SharedComponentRepository repo; private final SharedComponentRevisionRepository revisions; private final ContentTypeRepository types; private final PageVisualCompositionRepository compositions; private final ObjectMapper mapper; private final AuditService audit; private final RenderDatasetCache cache;
 SharedComponentService(com.powercms.cms.content.repo.ContentItemRepository items,SharedComponentRepository r,SharedComponentRevisionRepository v,ContentTypeRepository t,PageVisualCompositionRepository p,ObjectMapper m,AuditService a,RenderDatasetCache c){this.items=items;repo=r;revisions=v;types=t;compositions=p;mapper=m;audit=a;cache=c;}
 public record Info(UUID id,UUID siteId,String path,String title,UUID contentTypeId,Map<String,Object> data,String templatePath,String status,int currentVersion,long lockVersion,String createdBy,Instant createdAt,Instant updatedAt){}
 public record Revision(UUID id,int version,Integer parentVersion,String author,String message,Instant createdAt){}
 public record Reference(UUID pageId,UUID compositionId,int compositionVersion,String zoneKey,String instanceId){}
 public List<Info> list(UUID siteId,String q){String query=q==null?"":q.trim().toLowerCase(Locale.ROOT);return repo.findBySiteIdOrderByPathAsc(siteId).stream().filter(x->query.isEmpty()||x.getPath().toLowerCase(Locale.ROOT).contains(query)||x.getTitle().toLowerCase(Locale.ROOT).contains(query)).map(this::info).toList();}
 public Info get(UUID siteId,UUID id){return info(require(siteId,id));}
 public Info byPath(UUID siteId,String path){return info(repo.findBySiteIdAndPath(siteId,normalizePath(path)).orElseThrow(()->missing("共享组件不存在")));}
 public Info save(UUID siteId,UUID id,String path,String title,UUID typeId,Map<String,Object> data,String templatePath,String status,Long lockVersion,String actor,String message){
  var type=types.findById(typeId).filter(t->siteId.equals(t.getSiteId())&&"COMPONENT".equalsIgnoreCase(t.getObjectType())).orElseThrow(()->bad("内容类型不存在或不是 COMPONENT 类型"));
  var e=id==null?new SharedComponentEntity():require(siteId,id);if(id!=null&&lockVersion!=null&&e.getLockVersion()!=lockVersion)throw conflict();
  e.setSiteId(siteId);e.setPath(normalizePath(path));e.setTitle(title==null||title.isBlank()?type.getName():title.trim());e.setContentTypeId(typeId);e.setDataJson(write(normalizeData(data)));e.setTemplatePath(ComponentNormalizer.normalizeTemplate(templatePath));e.setStatus(normalizeStatus(status));if(id==null)e.setCreatedBy(actor);
  try{e=repo.saveAndFlush(e);}catch(ObjectOptimisticLockingFailureException ex){throw conflict();}catch(org.springframework.dao.DataIntegrityViolationException ex){throw new BusinessException(ErrorCodes.CONFLICT,"组件路径已存在");}
  snapshot(e,actor,message==null?(id==null?"创建共享组件":"更新共享组件"):message);cache.invalidate(RenderDatasetCache.Region.CONTENT,"component:"+e.getId());audit.write(actor,id==null?"SHARED_COMPONENT_CREATE":"SHARED_COMPONENT_UPDATE","SHARED_COMPONENT",e.getId().toString(),siteId,"SUCCESS",e.getPath());return info(e);
 }
 public List<Revision> history(UUID siteId,UUID id){require(siteId,id);return revisions.findByComponentIdOrderByVersionDesc(id).stream().map(x->new Revision(x.getId(),x.getVersion(),x.getParentVersion(),x.getAuthor(),x.getMessage(),x.getCreatedAt())).toList();}
 public Info restore(UUID siteId,UUID id,int version,String actor){var e=require(siteId,id);var r=revisions.findByComponentIdAndVersion(id,version).orElseThrow(()->missing("组件版本不存在"));Map<String,Object>s=read(r.getSnapshot());e.setPath(String.valueOf(s.get("path")));e.setTitle(String.valueOf(s.get("title")));e.setContentTypeId(UUID.fromString(String.valueOf(s.get("contentTypeId"))));e.setDataJson(write(s.getOrDefault("data",Map.of())));e.setTemplatePath((String)s.get("templatePath"));e.setStatus(String.valueOf(s.get("status")));repo.save(e);snapshot(e,actor,"恢复版本 "+version);cache.invalidate(RenderDatasetCache.Region.CONTENT,"component:"+id);return info(e);}
 public List<Reference> references(UUID siteId,UUID id){require(siteId,id);List<Reference> out=new ArrayList<>();for(var c:compositions.findAll()){if(items.findById(c.getPageId()).filter(p->siteId.equals(p.getSiteId())).isEmpty())continue; for(var z:parseZones(c.getZonesJson()).entrySet())for(var i:z.getValue())if(id.equals(i.sharedComponentId()))out.add(new Reference(c.getPageId(),c.getId(),c.getVersion(),z.getKey(),i.id()));}return out;}
 public void delete(UUID siteId,UUID id,String actor){var e=require(siteId,id);var refs=references(siteId,id);if(!refs.isEmpty())throw new BusinessException(ErrorCodes.CONFLICT,"组件仍被 "+refs.size()+" 个页面引用，无法删除");revisions.deleteByComponentId(id);repo.delete(e);cache.invalidate(RenderDatasetCache.Region.CONTENT,"component:"+id);audit.write(actor,"SHARED_COMPONENT_DELETE","SHARED_COMPONENT",id.toString(),siteId,"SUCCESS",e.getPath());}
 private void snapshot(SharedComponentEntity e,String actor,String msg){int next=e.getCurrentVersion()+1;var snap=new LinkedHashMap<String,Object>();snap.put("path",e.getPath());snap.put("title",e.getTitle());snap.put("contentTypeId",e.getContentTypeId());snap.put("data",read(e.getDataJson()));snap.put("templatePath",e.getTemplatePath());snap.put("status",e.getStatus());revisions.save(SharedComponentRevisionEntity.create(e.getId(),next,e.getCurrentVersion()>0?e.getCurrentVersion():null,write(snap),actor,msg));e.setCurrentVersion(next);repo.save(e);}
 private Map<String,Object> normalizeData(Map<String,Object>d){try{byte[] b=mapper.writeValueAsBytes(d==null?Map.of():d);if(b.length>ComponentNormalizer.MAX_BYTES)throw bad("组件数据超过 256 KiB 上限");return d==null?Map.of():new LinkedHashMap<>(d);}catch(BusinessException x){throw x;}catch(Exception x){throw bad("组件数据不是合法 JSON");}}
 private Map<String,List<ComponentInstance>> parseZones(String raw){try{return mapper.readValue(raw,new TypeReference<>(){});}catch(Exception x){return Map.of();}}
 private SharedComponentEntity require(UUID siteId,UUID id){return repo.findById(id).filter(x->siteId.equals(x.getSiteId())).orElseThrow(()->missing("共享组件不存在"));}
 private Info info(SharedComponentEntity e){return new Info(e.getId(),e.getSiteId(),e.getPath(),e.getTitle(),e.getContentTypeId(),read(e.getDataJson()),e.getTemplatePath(),e.getStatus(),e.getCurrentVersion(),e.getLockVersion(),e.getCreatedBy(),e.getCreatedAt(),e.getUpdatedAt());}
 private String normalizePath(String raw){if(raw==null)throw bad("路径不能为空");String p=raw.trim().replace('\\','/').replaceAll("/{2,}","/");if(!p.startsWith("/site/components/")||p.contains("..")||p.length()>1000)throw bad("路径必须以 /site/components/ 开头");return p;}
 private String normalizeStatus(String s){String v=s==null?"DRAFT":s.toUpperCase(Locale.ROOT);if(!Set.of("DRAFT","PUBLISHED","ARCHIVED").contains(v))throw bad("非法状态: "+s);return v;}
 private Map<String,Object> read(String raw){try{return mapper.readValue(raw,new TypeReference<>(){});}catch(Exception x){throw bad("组件数据不是合法 JSON");}} private String write(Object v){try{return mapper.writeValueAsString(v);}catch(Exception x){throw bad("组件数据序列化失败");}}
 private BusinessException conflict(){return new BusinessException(ErrorCodes.CONTENT_VERSION_CONFLICT,"组件已被他人修改，请刷新后重试");} private BusinessException bad(String m){return new BusinessException(ErrorCodes.VALIDATION_FAILED,m);} private BusinessException missing(String m){return new BusinessException(ErrorCodes.NOT_FOUND,m);}
}
