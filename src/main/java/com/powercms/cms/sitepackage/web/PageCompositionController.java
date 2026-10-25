package com.powercms.cms.sitepackage.web;
import com.powercms.cms.sitepackage.*;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/sites/{siteId}/pages/{pageId}/composition")
class PageCompositionController{
 private final PageCompositionService service;private final com.powercms.cms.delivery.PageRenderService renderer;
 PageCompositionController(PageCompositionService s,com.powercms.cms.delivery.PageRenderService r){service=s;renderer=r;}
 record SaveReq(@NotNull UUID templateId,@NotNull Map<String,List<ComponentInstance>> zones,@Min(0) int expectedVersion){}
 record AddReq(@NotBlank String zoneKey,@NotNull ComponentInstance component,@Min(0) int expectedVersion){}
 record UpdateReq(@NotNull ComponentInstance component,@Min(0) int expectedVersion){}
 record MoveReq(@NotBlank String targetZone,@Min(0) int targetIndex,@Min(0) int expectedVersion){}
 record LayoutReq(@NotBlank String layoutJson,@Min(0) int expectedVersion){}
 record RenderReq(@NotBlank String zoneKey,@Min(0) int index,@NotNull ComponentInstance component){}
 record RenderRes(String html){}
 /** 可视化编辑器局部刷新：渲染单个组件（可带未保存数据）返回带标注的 HTML。 */
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PostMapping("/component-preview") RenderRes componentPreview(@PathVariable UUID siteId,@PathVariable UUID pageId,@Valid @RequestBody RenderReq r){return new RenderRes(renderer.renderComponentEditPreview(siteId,pageId,r.zoneKey(),r.index(),r.component()));}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')") @GetMapping PageCompositionService.CompositionInfo get(@PathVariable UUID siteId,@PathVariable UUID pageId){return service.get(siteId,pageId);}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write') and @siteAuthorization.visualTemplate(authentication,#r.templateId(),'use')") @PutMapping PageCompositionService.CompositionInfo save(@PathVariable UUID siteId,@PathVariable UUID pageId,@Valid @RequestBody SaveReq r,Authentication a){return service.save(siteId,pageId,r.templateId(),r.zones(),r.expectedVersion(),a.getName());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PutMapping("/layout") PageCompositionService.CompositionInfo saveLayout(@PathVariable UUID siteId,@PathVariable UUID pageId,@Valid @RequestBody LayoutReq r,Authentication a){return service.savePageLayout(siteId,pageId,r.layoutJson(),r.expectedVersion(),a.getName());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @DeleteMapping PageCompositionService.CompositionInfo unbind(@PathVariable UUID siteId,@PathVariable UUID pageId,@RequestParam int expectedVersion,Authentication a){return service.unbind(siteId,pageId,expectedVersion,a.getName());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PostMapping("/components") PageCompositionService.CompositionInfo add(@PathVariable UUID siteId,@PathVariable UUID pageId,@Valid @RequestBody AddReq r,Authentication a){return service.add(siteId,pageId,r.zoneKey(),r.component(),r.expectedVersion(),a.getName());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PutMapping("/components/{instanceId}") PageCompositionService.CompositionInfo update(@PathVariable UUID siteId,@PathVariable UUID pageId,@PathVariable String instanceId,@Valid @RequestBody UpdateReq r,Authentication a){return service.update(siteId,pageId,instanceId,r.component(),r.expectedVersion(),a.getName());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @DeleteMapping("/components/{instanceId}") PageCompositionService.CompositionInfo delete(@PathVariable UUID siteId,@PathVariable UUID pageId,@PathVariable String instanceId,@RequestParam int expectedVersion,Authentication a){return service.delete(siteId,pageId,instanceId,expectedVersion,a.getName());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PostMapping("/components/{instanceId}/move") PageCompositionService.CompositionInfo move(@PathVariable UUID siteId,@PathVariable UUID pageId,@PathVariable String instanceId,@Valid @RequestBody MoveReq r,Authentication a){return service.move(siteId,pageId,instanceId,r.targetZone(),r.targetIndex(),r.expectedVersion(),a.getName());}
}
