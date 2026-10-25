package com.powercms.cms.sitepackage;
import java.util.Map; import java.util.UUID;
/** One page-placement. CONTENT references a persisted content_item; id remains the placement identity. */
public record ComponentInstance(String id, Kind kind, UUID sharedComponentId, Integer sharedVersion,
 UUID componentTypeId, String title, Map<String,Object> data, String templatePath, UUID containerId,
 Map<String,Object> additionalModel, int order, UUID widgetId, Map<String,Object> widgetParams,
 UUID contentItemId, Integer contentVersion) {
 public ComponentInstance(String id,Kind kind,UUID sharedComponentId,Integer sharedVersion,UUID componentTypeId,
  String title,Map<String,Object> data,String templatePath,UUID containerId,Map<String,Object> additionalModel,int order){
  this(id,kind,sharedComponentId,sharedVersion,componentTypeId,title,data,templatePath,containerId,additionalModel,order,null,Map.of(),null,null);
 }
 public enum Kind { EMBEDDED, CONTENT, SHARED, WIDGET }
}
