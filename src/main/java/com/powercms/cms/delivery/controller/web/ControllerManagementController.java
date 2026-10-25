package com.powercms.cms.delivery.controller.web;

import com.powercms.cms.delivery.controller.ControllerScriptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/sites/{siteId}/mvc/controllers")
public class ControllerManagementController {
 private final ControllerScriptService service;
 ControllerManagementController(ControllerScriptService service){this.service=service;}
 public record SaveRequest(UUID id,@NotBlank String path,@NotBlank String definition){}
 public record ValidateRequest(UUID id,@NotBlank String path,@NotBlank String definition){}
 public record PublishRequest(String message){}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')") @GetMapping public List<ControllerScriptService.Info> list(@PathVariable UUID siteId){return service.list(siteId);}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')") @GetMapping("/{id}") public ControllerScriptService.Info get(@PathVariable UUID siteId,@PathVariable UUID id){return service.get(siteId,id);}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PostMapping("/validate") public ControllerScriptService.Validation validate(@PathVariable UUID siteId,@Valid @RequestBody ValidateRequest r){return service.validate(siteId,r.path(),r.definition(),r.id());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PostMapping public ResponseEntity<ControllerScriptService.Info> create(@PathVariable UUID siteId,@Valid @RequestBody SaveRequest r,Principal p){return ResponseEntity.status(201).body(service.save(siteId,null,r.path(),r.definition(),actor(p)));}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PutMapping("/{id}") public ControllerScriptService.Info update(@PathVariable UUID siteId,@PathVariable UUID id,@Valid @RequestBody SaveRequest r,Principal p){return service.save(siteId,id,r.path(),r.definition(),actor(p));}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @PostMapping("/{id}/publish") public ControllerScriptService.Info publish(@PathVariable UUID siteId,@PathVariable UUID id,@RequestBody(required=false) PublishRequest r,Principal p){return service.publish(siteId,id,actor(p),r==null?null:r.message());}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'write')") @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID siteId,@PathVariable UUID id,Principal p){service.delete(siteId,id,actor(p));return ResponseEntity.noContent().build();}
 @PreAuthorize("@siteAuthorization.check(authentication,#siteId,'read')") @RequestMapping(value="/preview/**",method={RequestMethod.GET,RequestMethod.POST}) public ResponseEntity<byte[]> preview(@PathVariable UUID siteId,HttpServletRequest req,@RequestBody(required=false) byte[] body){return execute(siteId,routeAfter(req,"/preview"),req,body,true);}
 private ResponseEntity<byte[]> execute(UUID siteId,String route,HttpServletRequest req,byte[] body,boolean preview){if(body!=null&&body.length>65536)return ResponseEntity.status(413).body(new byte[0]);var result=service.executeRoute(siteId,route,req.getMethod(),preview,safeRequest(req,body));if(result.direct())return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.mediaType())).header("Cache-Control","no-store").body(result.body());String html=service.renderView(siteId,result,Map.of(),safeRequest(req,body),preview);return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Cache-Control","no-store").body(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 static Map<String,Object> safeRequest(HttpServletRequest r,byte[] body){Map<String,Object> out=new LinkedHashMap<>();Map<String,String> params=new LinkedHashMap<>();r.getParameterMap().forEach((k,v)->{if(params.size()<32&&k.length()<=64)params.put(k,v.length==0?"":v[0].substring(0,Math.min(v[0].length(),1024)));});out.put("method",r.getMethod());out.put("path",r.getRequestURI());out.put("parameters",params);out.put("body",body==null?"":new String(body,java.nio.charset.StandardCharsets.UTF_8));return Collections.unmodifiableMap(out);}
 static String routeAfter(HttpServletRequest r,String marker){String uri=r.getRequestURI();int i=uri.indexOf(marker);String route=i<0?"/":uri.substring(i+marker.length());return route.isBlank()?"/":route;}
 private static String actor(Principal p){return p==null?"system":p.getName();}
}
