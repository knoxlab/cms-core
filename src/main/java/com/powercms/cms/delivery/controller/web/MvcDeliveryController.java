package com.powercms.cms.delivery.controller.web;

import com.powercms.cms.delivery.controller.ControllerScriptService;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class MvcDeliveryController {
 private final ControllerScriptService service; private final SiteRepository sites; private final UrlRewriteService rewrites;
 MvcDeliveryController(ControllerScriptService service,SiteRepository sites,UrlRewriteService rewrites){this.service=service;this.sites=sites;this.rewrites=rewrites;}
 @RequestMapping(value="/mvc/sites/{siteKey}/**",method={RequestMethod.GET,RequestMethod.POST})
 ResponseEntity<byte[]> delivery(@PathVariable String siteKey,HttpServletRequest req,@RequestBody(required=false) byte[] body){
   if(body!=null&&body.length>65536)return ResponseEntity.status(413).body(new byte[0]);
   var site=sites.findBySiteKey(siteKey).orElseThrow(()->new BusinessException(ErrorCodes.NOT_FOUND,"站点不存在"));
   String route=rewrites.rewrite(site.getId(),ControllerManagementController.routeAfter(req,"/mvc/sites/"+siteKey),false);
   Map<String,Object> safe=ControllerManagementController.safeRequest(req,body);
   var result=service.executeRoute(site.getId(),route,req.getMethod(),false,safe);
   if(result.direct())return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.mediaType())).header("Cache-Control","public, max-age=30").body(result.body());
   String html=service.renderView(site.getId(),result,Map.of(),safe,false);
   return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Cache-Control","public, max-age=30").body(html.getBytes(StandardCharsets.UTF_8));
 }
}
