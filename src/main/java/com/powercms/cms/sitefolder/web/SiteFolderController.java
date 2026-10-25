package com.powercms.cms.sitefolder.web;
import com.powercms.cms.sitefolder.SiteFolderService;import jakarta.validation.Valid;import jakarta.validation.constraints.NotBlank;import org.springframework.http.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/sites/{siteId}/folders")
class SiteFolderController{
 private final SiteFolderService service;SiteFolderController(SiteFolderService service){this.service=service;}
 record Req(UUID parentId,@NotBlank String title,@NotBlank String name,Integer sortOrder,Boolean showOnMenu,String allowedFileExtensions,String defaultFileAssetType){}
 @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')") @GetMapping List<SiteFolderService.Info> tree(@PathVariable UUID siteId){return service.tree(siteId);}
 @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')") @GetMapping("/{id}") SiteFolderService.Info get(@PathVariable UUID siteId,@PathVariable UUID id){return service.get(siteId,id);}
 @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')") @PostMapping ResponseEntity<SiteFolderService.Info> create(@PathVariable UUID siteId,@Valid @RequestBody Req r,Authentication a){return ResponseEntity.status(HttpStatus.CREATED).body(service.create(siteId,r.parentId(),r.title(),r.name(),r.sortOrder(),r.showOnMenu(),r.allowedFileExtensions(),r.defaultFileAssetType(),actor(a)));}
 @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')") @PutMapping("/{id}") SiteFolderService.Info update(@PathVariable UUID siteId,@PathVariable UUID id,@Valid @RequestBody Req r,Authentication a){return service.update(siteId,id,r.title(),r.name(),r.sortOrder(),r.showOnMenu(),r.allowedFileExtensions(),r.defaultFileAssetType(),actor(a));}
 @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'write')") @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable UUID siteId,@PathVariable UUID id,Authentication a){service.delete(siteId,id,actor(a));return ResponseEntity.noContent().build();}
 private static String actor(Authentication a){return a==null||a.getName()==null?"system":a.getName();}
}
