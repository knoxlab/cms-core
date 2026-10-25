package com.powercms.cms.delivery.controller;

import com.fasterxml.jackson.databind.*;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.FreeMarkerSandbox;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.common.error.*;
import freemarker.core.Environment;
import freemarker.template.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** P2-2 safe baseline: strict JSON DSL only. No Groovy/Java evaluation or user beans. */
@Service
@Transactional
public class ControllerScriptService {
    private static final Set<String> ROOT=Set.of("route","method","view","direct","templateModel","queries","siteConfig");
    private static final Set<String> METHODS=Set.of("GET","POST");
    private static final Set<String> MEDIA=Set.of("application/json","application/xml","text/plain");
    private final SiteControllerRepository repo; private final SiteControllerVersionRepository versions; private final ObjectMapper mapper;
    private final SiteScopedContentQueryService content; private final TemplateService templates; private final FreeMarkerSandbox sandbox;
    private final RenderDatasetCache cache; private final AuditService audit; private final ThreadPoolExecutor executor;
    private final int timeoutMs,maxOutput;
    ControllerScriptService(SiteControllerRepository repo,SiteControllerVersionRepository versions,ObjectMapper mapper,
      SiteScopedContentQueryService content,TemplateService templates,FreeMarkerSandbox sandbox,RenderDatasetCache cache,AuditService audit,
      @Value("${powercms.mvc.timeout-ms:500}") int timeoutMs,@Value("${powercms.mvc.max-output-bytes:262144}") int maxOutput){
      this.repo=repo;this.versions=versions;this.mapper=mapper;this.content=content;this.templates=templates;this.sandbox=sandbox;this.cache=cache;this.audit=audit;this.timeoutMs=Math.max(50,Math.min(timeoutMs,5000));this.maxOutput=Math.max(1024,Math.min(maxOutput,1048576));
      this.executor=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"mvc-controller");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public record Info(UUID id,UUID siteId,String path,String route,String method,String definition,String status,int currentVersion,Integer publishedVersion,Instant updatedAt){}
    public record Validation(boolean valid,List<String> errors,String normalizedRoute,String method){}
    public record Result(String view,String mediaType,byte[] body,Map<String,Object> templateModel) { public boolean direct(){return view==null;} }
    private Info info(SiteControllerEntity e){return new Info(e.getId(),e.getSiteId(),e.getPath(),e.getRoutePath(),e.getHttpMethod(),e.getDefinition(),e.getStatus(),e.getCurrentVersion(),e.getPublishedVersion(),e.getUpdatedAt());}
    public List<Info> list(UUID siteId){return repo.findBySiteIdOrderByPathAsc(siteId).stream().map(this::info).toList();}
    public Info get(UUID siteId,UUID id){return info(require(siteId,id));}
    public Validation validate(UUID siteId,String path,String raw,UUID excluding){
      List<String> errors=new ArrayList<>();
      validatePath(path,errors);
      JsonNode n=parse(raw,errors);
      String route="/",method="GET";
      boolean routeValid=false,methodValid=false;
      if(n!=null){
        n.fieldNames().forEachRemaining(k->{if(!ROOT.contains(k))errors.add("Forbidden field: "+k);});
        String routeInput=text(n,"route",errors);
        String methodInput=text(n,"method",errors);
        if(!routeInput.isBlank()){
          try{route=normalizeRoute(routeInput);routeValid=true;}
          catch(BusinessException ex){errors.add(ex.getMessage());}
        }
        if(!methodInput.isBlank()){
          method=methodInput.toUpperCase(Locale.ROOT);
          methodValid=METHODS.contains(method);
          if(!methodValid)errors.add("method must be GET or POST");
        }
        validateView(n.path("view"),errors);
        validateDirect(n.path("direct"),errors);
        validateQueries(n.path("queries"),errors);
        if(routeValid&&methodValid){
          boolean conflict=excluding==null?repo.existsBySiteIdAndRoutePathAndHttpMethod(siteId,route,method):repo.existsBySiteIdAndRoutePathAndHttpMethodAndIdNot(siteId,route,method,excluding);
          if(conflict)errors.add("route + method conflict");
        }
      }
      return new Validation(errors.isEmpty(),List.copyOf(errors),route,method);
    }
    public Info save(UUID siteId,UUID id,String path,String definition,String actor){
      Validation v=validate(siteId,path,definition,id); if(!v.valid())throw bad(String.join("; ",v.errors())); SiteControllerEntity e=id==null?new SiteControllerEntity():require(siteId,id);
      e.setSiteId(siteId);e.setPath(normalizeScriptPath(path));e.setRoutePath(v.normalizedRoute());e.setHttpMethod(v.method());e.setDefinition(canonical(definition));e.setCreatedBy(actor);e=repo.save(e);cache.invalidatePrefix(RenderDatasetCache.Region.SCRIPT,siteId+":");audit.write(actor,"MVC_CONTROLLER_SAVE","SITE_CONTROLLER",e.getId().toString(),siteId,"SUCCESS","安全 JSON DSL");return info(e);
    }
    public Info publish(UUID siteId,UUID id,String actor,String message){SiteControllerEntity e=require(siteId,id);Validation v=validate(siteId,e.getPath(),e.getDefinition(),e.getId());if(!v.valid())throw bad(String.join("; ",v.errors()));int ver=e.getCurrentVersion()+1;versions.save(SiteControllerVersionEntity.create(e.getId(),ver,e.getDefinition(),sha256(e.getDefinition()),actor,message));e.setCurrentVersion(ver);e.setPublishedVersion(ver);e.setStatus("PUBLISHED");repo.save(e);cache.invalidatePrefix(RenderDatasetCache.Region.SCRIPT,siteId+":");audit.write(actor,"MVC_CONTROLLER_PUBLISH","SITE_CONTROLLER",id.toString(),siteId,"SUCCESS","version="+ver);return info(e);}
    public void delete(UUID siteId,UUID id,String actor){SiteControllerEntity e=require(siteId,id);repo.delete(e);cache.invalidatePrefix(RenderDatasetCache.Region.SCRIPT,siteId+":");audit.write(actor,"MVC_CONTROLLER_DELETE","SITE_CONTROLLER",id.toString(),siteId,"SUCCESS",null);}
    @Transactional(readOnly=true) public Result executeRoute(UUID siteId,String route,String method,boolean preview,Map<String,Object> request){String r=normalizeRoute(route),m=method.toUpperCase(Locale.ROOT);SiteControllerEntity e=repo.findBySiteIdAndRoutePathAndHttpMethodAndStatus(siteId,r,m,"PUBLISHED").orElseThrow(()->new BusinessException(ErrorCodes.NOT_FOUND,"控制器路由不存在"));return execute(e,preview,request,0,new Budget(timeoutMs));}
    @Transactional(readOnly=true) public Result executePath(UUID siteId,String path,boolean preview,Map<String,Object> request,int depth,Budget budget){if(depth>4)throw bad("inline controller 递归深度超过 4");String normalized=normalizeScriptPath(path);SiteControllerEntity e=repo.findBySiteIdOrderByPathAsc(siteId).stream().filter(x->x.getPath().equals(normalized)&&(preview||"PUBLISHED".equals(x.getStatus()))).findFirst().orElseThrow(()->new BusinessException(ErrorCodes.NOT_FOUND,"inline controller 不存在"));return execute(e,preview,request,depth,budget);}
    private Result execute(SiteControllerEntity e,boolean preview,Map<String,Object> request,int depth,Budget budget){long remaining=budget.remaining();if(remaining<=0)throw timeout(e);try{Future<Result> f=executor.submit(()->evaluate(e,preview,request,depth,budget));Result r=f.get(remaining,TimeUnit.MILLISECONDS);if(r.body()!=null&&r.body().length>maxOutput)throw resource("控制器输出超过限制");return r;}catch(TimeoutException ex){throw timeout(e);}catch(RejectedExecutionException ex){throw resource("控制器执行队列已满");}catch(ExecutionException ex){if(ex.getCause() instanceof RuntimeException re)throw re;throw resource("控制器执行失败");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw resource("控制器执行被中断");}}
    private Result evaluate(SiteControllerEntity e,boolean preview,Map<String,Object> request,int depth,Budget budget){JsonNode n=read(e.getDefinition());Map<String,Object> model=new LinkedHashMap<>();if(n.path("templateModel").isObject())model.putAll(mapper.convertValue(n.path("templateModel"),Map.class));for(JsonNode q:n.path("queries")){String as=q.path("as").asText();String op=q.path("op").asText();int limit=Math.min(q.path("limit").asInt(20),50);Object value="byRoute".equals(op)?content.byRoute(e.getSiteId(),q.path("route").asText(),q.path("status").asText("PUBLISHED")):content.query(e.getSiteId(),q.path("status").asText("PUBLISHED"),limit);model.put(as,value);}if(n.has("siteConfig"))model.put("siteConfig",mapper.convertValue(n.path("siteConfig"),Map.class));if(n.has("direct")){JsonNode d=n.path("direct");String mt=d.path("mediaType").asText();byte[] body=(d.path("body").isTextual()?d.path("body").asText():write(d.path("body"))).getBytes(StandardCharsets.UTF_8);return new Result(null,mt,body,model);}String view=n.path("view").asText();return new Result(view,"text/html",null,model);}
    public String renderView(UUID siteId,Result result,Map<String,Object> contentModel,Map<String,Object> request,boolean preview){if(result.view()==null)throw bad("direct response 无视图");String path=result.view().substring(1);String source=resolveTemplate(siteId,path,!preview);Map<String,Object> model=new LinkedHashMap<>();model.put("contentModel",contentModel==null?Map.of():contentModel);model.put("templateModel",result.templateModel());model.put("request",request==null?Map.of():request);model.put("controller",new InlineDirective(siteId,result.templateModel(),request,preview,new Budget(timeoutMs),0));String html=sandbox.render(path,source,model,preview);if(html.getBytes(StandardCharsets.UTF_8).length>maxOutput)throw resource("视图输出超过限制");return html;}
    public final class InlineDirective implements TemplateDirectiveModel {private final UUID siteId;private final Map<String,Object> target;private final Map<String,Object> request;private final boolean preview;private final Budget budget;private final int depth;InlineDirective(UUID s,Map<String,Object> t,Map<String,Object> r,boolean p,Budget b,int d){siteId=s;target=t;request=r;preview=p;budget=b;depth=d;}public void execute(Environment env,Map params,TemplateModel[] loop,TemplateDirectiveBody body)throws TemplateException,IOException{Object raw=params.get("path");String path=raw==null?"":raw.toString();Result nested=executePath(siteId,path,preview,request,depth+1,budget);target.putAll(nested.templateModel());}}
    public static final class Budget{private final long deadline;public Budget(long ms){deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);}long remaining(){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}}
    private String resolveTemplate(UUID siteId,String path,boolean cacheIt){for(var p:templates.listProjects(siteId)){if(!"FTL".equalsIgnoreCase(p.projectType()))continue;try{return cache.getOrLoad(RenderDatasetCache.Region.TEMPLATE,p.id()+":"+path,cacheIt,()->templates.readFileContent(p.id(),path));}catch(BusinessException ignored){}}throw new BusinessException(ErrorCodes.NOT_FOUND,"视图不存在: /"+path);}
    private void validateQueries(JsonNode q,List<String> e){if(q.isMissingNode())return;if(!q.isArray()||q.size()>10){e.add("queries 必须为最多 10 项数组");return;}Set<String> names=new HashSet<>();for(JsonNode x:q){Set<String> allowed=Set.of("as","op","route","status","limit");x.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))e.add("query 禁止字段: "+k);});String as=x.path("as").asText();if(!as.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||!names.add(as))e.add("query.as 非法或重复");String op=x.path("op").asText();if(!Set.of("list","byRoute").contains(op))e.add("query.op 仅允许 list/byRoute");if(x.path("limit").asInt(20)>50)e.add("query.limit 最大 50");}}
    private void validateView(JsonNode v,List<String> e){if(v.isMissingNode())return;String p=v.asText();if(!p.matches("^/templates/[A-Za-z0-9_./-]+\\.ftl$")||p.contains(".."))e.add("view 必须是站内 /templates/...ftl");}
    private void validateDirect(JsonNode d,List<String> e){if(d.isMissingNode())return;if(!d.isObject()){e.add("direct 必须是对象");return;}d.fieldNames().forEachRemaining(k->{if(!Set.of("mediaType","body").contains(k))e.add("direct 禁止字段: "+k);});if(!MEDIA.contains(d.path("mediaType").asText()))e.add("direct mediaType 不允许");try{if(write(d.path("body")).getBytes(StandardCharsets.UTF_8).length>maxOutput)e.add("direct body 超过限制");}catch(Exception ex){e.add("direct body 非法");}}
    private void validatePath(String p,List<String> e){try{normalizeScriptPath(p);}catch(BusinessException ex){e.add(ex.getMessage());}}
    private String normalizeScriptPath(String p){if(p==null||!p.matches("^/scripts/controllers/[A-Za-z0-9_./-]+\\.controller\\.json$")||p.contains("..")||p.contains("//"))throw bad("path 必须是 /scripts/controllers/**.controller.json");return p;}
    private String normalizeRoute(String p){if(p==null||p.isBlank())return "/";String r=p.trim();if(!r.startsWith("/"))r="/"+r;if(r.contains("..")||r.contains("\\")||r.contains("//")||r.startsWith("/api"))throw bad("非法或保留 route");if(r.length()>1&&r.endsWith("/"))r=r.substring(0,r.length()-1);return r;}
    private JsonNode parse(String raw,List<String> e){try{JsonNode n=mapper.readTree(raw);if(!n.isObject()){e.add("定义必须为 JSON object");return null;}return n;}catch(Exception ex){e.add("JSON 语法错误");return null;}}
    private JsonNode read(String raw){try{return mapper.readTree(raw);}catch(Exception ex){throw bad("JSON 定义损坏");}}
    private String canonical(String raw){try{return mapper.writeValueAsString(mapper.readTree(raw));}catch(Exception ex){throw bad("JSON 语法错误");}}
    private String write(JsonNode n){try{return mapper.writeValueAsString(n);}catch(Exception ex){throw bad("JSON 序列化失败");}}
    private String text(JsonNode n,String key,List<String> e){String v=n.path(key).asText("");if(v.isBlank())e.add(key+" 必填");return v;}
    private SiteControllerEntity require(UUID siteId,UUID id){return repo.findByIdAndSiteId(id,siteId).orElseThrow(()->new BusinessException(ErrorCodes.NOT_FOUND,"控制器不存在"));}
    private BusinessException timeout(SiteControllerEntity e){audit.write("delivery","MVC_CONTROLLER_EXECUTE","SITE_CONTROLLER",e.getId().toString(),e.getSiteId(),"TIMEOUT",MDC.get("traceId"),"执行预算耗尽");return new BusinessException(ErrorCodes.INTERNAL_ERROR,"控制器执行超时");}
    private BusinessException resource(String m){return new BusinessException(ErrorCodes.INTERNAL_ERROR,m);}
    private BusinessException bad(String m){return new BusinessException(ErrorCodes.VALIDATION_FAILED,m);}
    private String sha256(String s){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw resource("摘要失败");}}
}
