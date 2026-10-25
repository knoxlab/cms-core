package com.powercms.cms.sitepackage.web;
import com.powercms.cms.sitepackage.SharedComponentService;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/sites/{siteId}/components")
class SharedComponentController{
 private final SharedComponentService service;SharedComponentController(SharedComponentService s){service=s;}
 record SaveReq(@NotBlank String path,String title,@NotNull UUID contentTypeId,@NotNull Map<String,Object> data,String templatePath,String status,Long lockVersion,String message){}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')") @GetMapping List<SharedComponentService.Info> list(@PathVariable UUID siteId,@RequestParam(required=false)String q){return service.list(siteId,q);}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')") @GetMapping("/{id}") SharedComponentService.Info get(@PathVariable UUID siteId,@PathVariable UUID id){return service.get(siteId,id);}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PostMapping SharedComponentService.Info create(@PathVariable UUID siteId,@Valid @RequestBody SaveReq r,Authentication a){return service.save(siteId,null,r.path(),r.title(),r.contentTypeId(),r.data(),r.templatePath(),r.status(),null,a.getName(),r.message());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PutMapping("/{id}") SharedComponentService.Info update(@PathVariable UUID siteId,@PathVariable UUID id,@Valid @RequestBody SaveReq r,Authentication a){return service.save(siteId,id,r.path(),r.title(),r.contentTypeId(),r.data(),r.templatePath(),r.status(),r.lockVersion(),a.getName(),r.message());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')") @GetMapping("/{id}/history") List<SharedComponentService.Revision> history(@PathVariable UUID siteId,@PathVariable UUID id){return service.history(siteId,id);}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PostMapping("/{id}/restore/{version}") SharedComponentService.Info restore(@PathVariable UUID siteId,@PathVariable UUID id,@PathVariable int version,Authentication a){return service.restore(siteId,id,version,a.getName());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')") @GetMapping("/{id}/references") List<SharedComponentService.Reference> references(@PathVariable UUID siteId,@PathVariable UUID id){return service.references(siteId,id);}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @DeleteMapping("/{id}") void delete(@PathVariable UUID siteId,@PathVariable UUID id,Authentication a){service.delete(siteId,id,a.getName());}
}
