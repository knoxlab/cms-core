package com.powercms.cms.sitefolder;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitefolder.entity.SiteFolderEntity;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.Pattern;

@Service @Transactional
public class SiteFolderService {
 private static final Pattern NAME=Pattern.compile("[A-Za-z0-9_-]{1,120}");
 private static final Pattern EXT=Pattern.compile("\\.?[A-Za-z0-9]+(,\\s*\\.?[A-Za-z0-9]+)*");
 private static final Set<String> TYPES=Set.of("FILE","IMAGE","PAGE");
 private final SiteFolderRepository folders; private final SiteRepository sites; private final ContentItemRepository contentItems; private final AuditService audit; private final RenderDatasetCache cache;
 SiteFolderService(SiteFolderRepository folders,SiteRepository sites,ContentItemRepository contentItems,AuditService audit,RenderDatasetCache cache){this.folders=folders;this.sites=sites;this.contentItems=contentItems;this.audit=audit;this.cache=cache;}
 public record Info(UUID id,UUID siteId,UUID parentId,String title,String name,String path,int sortOrder,boolean showOnMenu,String allowedFileExtensions,String defaultFileAssetType,java.time.Instant createdAt,java.time.Instant updatedAt,List<Info> children){}
 /** Folder impact facts for MCP dry-run: subtree sizes, page routes under path, delete blockers (machine codes). */
 public record FolderImpact(UUID folderId,UUID parentId,String path,int descendantFolders,long contentsInSubtree,long directContents,long pagesUnderPath,boolean deletable,List<String> deleteBlockers,boolean pathChangeBlocked){}
 public List<Info> tree(UUID siteId){if(!sites.existsById(siteId))throw missing("\u7ad9\u70b9\u4e0d\u5b58\u5728");List<SiteFolderEntity> all=folders.findBySiteIdOrderBySortOrderAscTitleAsc(siteId);return children(all,null);}
 public Info get(UUID siteId,UUID id){return info(require(siteId,id),List.of());}
 public Info create(UUID siteId,UUID parentId,String title,String name,Integer sortOrder,Boolean show,String extensions,String type,String actor){
  if(!sites.existsById(siteId))throw missing("\u7ad9\u70b9\u4e0d\u5b58\u5728");SiteFolderEntity parent=parentId==null?null:require(siteId,parentId);SiteFolderEntity e=new SiteFolderEntity();e.setSiteId(siteId);e.setParentId(parentId);apply(e,parent,title,name,sortOrder,show,extensions,type);save(e);audit.write(actor,"SITE_FOLDER_CREATE","SITE_FOLDER",e.getId().toString(),siteId,"SUCCESS","path="+e.getPath());invalidate(siteId);return info(e,List.of());}
 public Info update(UUID siteId,UUID id,String title,String name,Integer sortOrder,Boolean show,String extensions,String type,String actor){
  SiteFolderEntity e=require(siteId,id),parent=e.getParentId()==null?null:require(siteId,e.getParentId());String old=e.getPath();apply(e,parent,title,name,sortOrder,show,extensions,type);if(!old.equals(e.getPath())&&contentItems.findBySiteId(siteId).stream().anyMatch(item->item.getRoutePath()!=null&&(item.getRoutePath().equals(old)||item.getRoutePath().startsWith(old+"/"))))throw conflict("\u6587\u4ef6\u5939\u4e0b\u5b58\u5728\u9875\u9762\uff1b\u8bf7\u5148\u64a4\u56de\u5e76\u901a\u8fc7\u7ad9\u70b9\u6d4f\u89c8\u5668\u79fb\u52a8\u9875\u9762\u5b50\u6811\uff0c\u518d\u4fee\u6539\u6587\u4ef6\u5939 URL");save(e);if(!old.equals(e.getPath()))moveDescendants(siteId,old,e.getPath());audit.write(actor,"SITE_FOLDER_UPDATE","SITE_FOLDER",id.toString(),siteId,"SUCCESS","oldPath="+old+"; path="+e.getPath());invalidate(siteId);return info(e,List.of());}
 public void delete(UUID siteId,UUID id,String actor){SiteFolderEntity e=require(siteId,id);if(folders.existsByParentId(id))throw conflict("\u6587\u4ef6\u5939\u5305\u542b\u5b50\u76ee\u5f55\uff0c\u65e0\u6cd5\u5220\u9664");if(contentItems.countByFolderId(id)>0)throw conflict("\u6587\u4ef6\u5939\u5305\u542b\u5185\u5bb9\uff0c\u65e0\u6cd5\u5220\u9664");if(contentItems.findBySiteId(siteId).stream().anyMatch(item->item.getFolderId()==null&&item.getRoutePath()!=null&&(item.getRoutePath().equals(e.getPath())||item.getRoutePath().startsWith(e.getPath()+"/"))))throw conflict("\u6587\u4ef6\u5939\u4e0b\u5b58\u5728\u672a\u5173\u8054\u7684\u9875\u9762\uff0c\u8bf7\u5148\u5173\u8054\u6216\u79fb\u9664");folders.delete(e);audit.write(actor,"SITE_FOLDER_DELETE","SITE_FOLDER",id.toString(),siteId,"SUCCESS","path="+e.getPath());invalidate(siteId);}
 /** Read-only impact facts: subtree folder/content counts, page routes under path, delete blockers and path-change blocking. */
 public FolderImpact assessImpact(UUID siteId,UUID id){
  SiteFolderEntity e=require(siteId,id);String path=e.getPath();List<SiteFolderEntity> all=folders.findBySiteIdOrderBySortOrderAscTitleAsc(siteId);
  List<UUID> subtree=new ArrayList<>();subtree.add(e.getId());int descendants=0;
  for(SiteFolderEntity f:all)if(f.getPath().startsWith(path+"/")){subtree.add(f.getId());descendants++;}
  long contents=0;for(UUID fid:subtree)contents+=contentItems.countByFolderId(fid);
  long direct=contentItems.countByFolderId(id);
  var items=contentItems.findBySiteId(siteId);
  long pages=items.stream().filter(i->i.getRoutePath()!=null&&(i.getRoutePath().equals(path)||i.getRoutePath().startsWith(path+"/"))).count();
  long unlinked=items.stream().filter(i->i.getFolderId()==null&&i.getRoutePath()!=null&&(i.getRoutePath().equals(path)||i.getRoutePath().startsWith(path+"/"))).count();
  List<String> blockers=new ArrayList<>();
  if(descendants>0)blockers.add("HAS_CHILD_FOLDERS");
  if(direct>0)blockers.add("HAS_CONTENT");
  if(unlinked>0)blockers.add("UNLINKED_PAGES");
  return new FolderImpact(e.getId(),e.getParentId(),path,descendants,contents,direct,pages,blockers.isEmpty(),List.copyOf(blockers),pages>0);
 }
 private void apply(SiteFolderEntity e,SiteFolderEntity parent,String title,String name,Integer sortOrder,Boolean show,String extensions,String type){
  String t=title==null?null:title.trim(),n=name==null?null:name.trim();if(t==null||t.isBlank()||t.length()>200)throw invalid("\u6807\u9898\u4e0d\u80fd\u4e3a\u7a7a\u4e14\u6700\u591a 200 \u5b57\u7b26");if(n==null||!NAME.matcher(n).matches())throw invalid("\u540d\u79f0\u4ec5\u5141\u8bb8\u5b57\u6bcd\u3001\u6570\u5b57\u3001\u4e0b\u5212\u7ebf\u548c\u8fde\u5b57\u7b26");if((parent==null?folders.existsBySiteIdAndParentIdIsNullAndName(e.getSiteId(),n):folders.existsBySiteIdAndParentIdAndName(e.getSiteId(),parent.getId(),n))&&!folders.findBySiteIdAndPath(e.getSiteId(),e.getPath()==null?"":e.getPath()).map(x->x.getId().equals(e.getId())).orElse(false))throw conflict("\u540c\u7ea7\u6587\u4ef6\u5939\u540d\u79f0\u5df2\u5b58\u5728");String path=(parent==null?"":parent.getPath())+"/"+n;if(path.length()>1000)throw invalid("\u6587\u4ef6\u5939\u8def\u5f84\u8d85\u8fc7 1000 \u5b57\u7b26");String ext=extensions==null||extensions.isBlank()?null:extensions.trim();if(ext!=null&&!EXT.matcher(ext).matches())throw invalid("\u5141\u8bb8\u6269\u5c55\u540d\u5fc5\u987b\u662f\u9017\u53f7\u5206\u9694\u7684\u6269\u5c55\u540d\u5217\u8868");String asset=type==null||type.isBlank()?"FILE":type.toUpperCase(Locale.ROOT);if(!TYPES.contains(asset))throw invalid("\u9ed8\u8ba4\u8d44\u6e90\u7c7b\u578b\u5fc5\u987b\u662f FILE\u3001IMAGE \u6216 PAGE");e.setTitle(t);e.setName(n);e.setPath(path);if(sortOrder!=null)e.setSortOrder(sortOrder);if(show!=null)e.setShowOnMenu(show);e.setAllowedFileExtensions(ext);e.setDefaultFileAssetType(asset);}
 private void moveDescendants(UUID siteId,String oldPath,String newPath){for(SiteFolderEntity child:folders.findBySiteIdOrderBySortOrderAscTitleAsc(siteId))if(child.getPath().startsWith(oldPath+"/")){child.setPath(newPath+child.getPath().substring(oldPath.length()));save(child);}}
 private SiteFolderEntity save(SiteFolderEntity e){try{return folders.saveAndFlush(e);}catch(DataIntegrityViolationException ex){throw conflict("\u540c\u7ea7\u540d\u79f0\u6216\u6587\u4ef6\u5939\u8def\u5f84\u5df2\u5b58\u5728");}}
 private SiteFolderEntity require(UUID siteId,UUID id){return folders.findByIdAndSiteId(id,siteId).orElseThrow(()->missing("\u6587\u4ef6\u5939\u4e0d\u5b58\u5728\u6216\u4e0d\u5c5e\u4e8e\u5f53\u524d\u7ad9\u70b9"));}
 private List<Info> children(List<SiteFolderEntity> all,UUID parent){return all.stream().filter(x->Objects.equals(x.getParentId(),parent)).sorted(Comparator.comparingInt(SiteFolderEntity::getSortOrder).thenComparing(SiteFolderEntity::getTitle,String.CASE_INSENSITIVE_ORDER)).map(x->info(x,children(all,x.getId()))).toList();}
 private Info info(SiteFolderEntity e,List<Info> c){return new Info(e.getId(),e.getSiteId(),e.getParentId(),e.getTitle(),e.getName(),e.getPath(),e.getSortOrder(),e.isShowOnMenu(),e.getAllowedFileExtensions(),e.getDefaultFileAssetType(),e.getCreatedAt(),e.getUpdatedAt(),c);}
 private void invalidate(UUID siteId){cache.invalidatePrefix(RenderDatasetCache.Region.CONTENT,"nav:"+siteId+":");}
 private static BusinessException invalid(String m){return new BusinessException(ErrorCodes.VALIDATION_FAILED,m);}private static BusinessException conflict(String m){return new BusinessException(ErrorCodes.CONFLICT,m);}private static BusinessException missing(String m){return new BusinessException(ErrorCodes.NOT_FOUND,m);}
}
