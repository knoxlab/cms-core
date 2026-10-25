package com.powercms.cms.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.cms.content.relation.ContentRelationRepository;
import com.powercms.cms.content.repo.*;
import com.powercms.cms.model.dto.*;
import com.powercms.cms.model.entity.*;
import com.powercms.cms.model.repo.*;
import com.powercms.cms.model.sandbox.ScriptSandboxService;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ContentTypeService {
  public static final Logger log = LoggerFactory.getLogger(ContentTypeService.class);
  private final ContentTypeRepository ctRepo;
  private final FieldDefinitionRepository fieldRepo;
  private final FormLayoutRepository layoutRepo;
  private final ContentTypeChangeLogRepository logRepo;
  private final ContentTypeLifecycleRepository lifecycleRepo;
  private final ContentItemRepository itemRepo;
  private final ContentRelationRepository relationRepo;
  private final SiteRepository siteRepo;
  private final ContentModelValidator validator;
  private final ScriptSandboxService sandbox;
  private final ObjectMapper om;
  private final ApplicationEventPublisher eventPublisher;
  private final ContentTypeMigrationService migrationService;

  ContentTypeService(
      ContentTypeRepository c,
      FieldDefinitionRepository f,
      FormLayoutRepository l,
      ContentTypeChangeLogRepository g,
      ContentTypeLifecycleRepository lifecycle,
      ContentItemRepository i,
      ContentRelationRepository relations,
      SiteRepository sites,
      ContentModelValidator v,
      ScriptSandboxService sandbox,
      ObjectMapper o,
      ApplicationEventPublisher events,
      ContentTypeMigrationService migration) {
    ctRepo = c;
    fieldRepo = f;
    layoutRepo = l;
    logRepo = g;
    lifecycleRepo = lifecycle;
    itemRepo = i;
    relationRepo = relations;
    siteRepo = sites;
    validator = v;
    this.sandbox = sandbox;
    om = o;
    eventPublisher = events;
    this.migrationService = migration;
  }

  public ContentTypeResponse create(UUID siteId, CreateContentTypeRequest req) {
    requireSite(siteId);
    validator.validate(req);
    validateInheritance(siteId, UUID.randomUUID(), req.parentTypeId());
    validateReferences(siteId, validator.normalized(req));
    if (ctRepo.existsBySiteIdAndCode(siteId, req.code()))
      throw new BusinessException(ErrorCodes.CONTENT_TYPE_CODE_DUPLICATE, "内容模型编码已存在");
    ContentTypeEntity ct = new ContentTypeEntity();
    ct.setSiteId(siteId);
    ct.setCode(req.code());
    apply(ct, req);
    ct = ctRepo.save(ct);
    saveDefinition(ct, validator.normalized(req));
    seedSystemFields(ct);
    return response(ct);
  }

  public ContentTypeResponse update(UUID siteId, UUID id, CreateContentTypeRequest req) {
    ContentTypeEntity ct = require(siteId, id);
    if (ContentTypeEntity.STATUS_PUBLISHED.equals(ct.getStatus()))
      throw new BusinessException(ErrorCodes.CONTENT_TYPE_PUBLISHED_READONLY, "已发布版本不可原地修改");
    String requested = req.templateType() == null ? ct.getTemplateType() : req.templateType();
    if (!ct.getTemplateType().equals(requested))
      throw new BusinessException(ErrorCodes.TEMPLATE_TYPE_IMMUTABLE, "模板类型不可修改");
    String objectType = req.objectType() == null ? ct.getObjectType() : req.objectType();
    if (!ct.getObjectType().equals(objectType))
      throw new BusinessException(ErrorCodes.OBJECT_TYPE_IMMUTABLE, "类型体系不可修改");
    CreateContentTypeRequest merged =
        new CreateContentTypeRequest(
            ct.getCode(),
            req.name(),
            req.description(),
            requested,
            req.displayTemplate(),
            req.icon(),
            req.thumbnail(), req.layout(), req.fields(), objectType, req.controllerPath(),
            req.formController(), req.mergeStrategy(), req.parentTypeId(), req.quickCreate(),
            req.destinationPattern(), req.previewImage(), req.config(), req.flattenComponents(),
            req.subtype(), req.formNotificationEmails(),
            req.mediaKind() == null ? ct.getMediaKind() : req.mediaKind(),
            req.schemaOrgType() == null ? ct.getSchemaOrgType() : req.schemaOrgType());
    validator.validate(merged);
    validateInheritance(siteId, id, merged.parentTypeId());
    validateReferences(siteId, validator.normalized(merged));
    apply(ct, merged);
    ctRepo.save(ct);
    layoutRepo.deleteByContentTypeId(id);
    deleteFields(id);
    saveDefinition(ct, validator.normalized(merged));
    seedSystemFields(ct);
    return response(ct);
  }

  public ContentTypeResponse updateConfig(UUID siteId, UUID id, JsonNode config) {
    ContentTypeEntity ct = require(siteId, id);
    if (ContentTypeEntity.STATUS_PUBLISHED.equals(ct.getStatus()))
      throw new BusinessException(
          ErrorCodes.CONTENT_TYPE_PUBLISHED_READONLY, "Published versions cannot be modified in place");
    validator.validateConfig(config);
    ct.setTypeConfig(json(config));
    return response(ctRepo.save(ct));
  }

  /**
   * 确保系统字段已挂载到布局（幂等，供存量媒体类型补齐 FR-CM-MEDIA-003 系统字段）。
   *
   * <p>种子创建的类型在早期版本只写了 field_definition 而遗漏 form_layout，
   * 本方法补挂载系统字段到根分区，不改动已有自定义布局。
   */
  public ContentTypeResponse ensureSystemFieldLayout(UUID siteId, UUID id) {
    ContentTypeEntity ct = require(siteId, id);
    seedSystemFields(ct);
    return response(ct);
  }

  public List<ContentTypeResponse> list(UUID siteId) {
    requireSite(siteId);
    return ctRepo.findBySiteIdOrderByCodeAsc(siteId).stream().map(this::response).toList();
  }

  public ContentTypeResponse get(UUID siteId, UUID id) {
    return response(require(siteId, id));
  }

  /** 已发布模型（同一编码取最新版本），供前台按表单布局渲染。 */
  public ContentTypeResponse publishedByCode(UUID siteId, String code) {
    requireSite(siteId);
    if (code == null || code.isBlank()) {
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "内容模型编码不能为空");
    }
    ContentTypeEntity type =
        ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, code.trim()).stream()
            .filter(item -> ContentTypeEntity.STATUS_PUBLISHED.equals(item.getStatus()))
            .reduce((left, right) -> right)
            .orElseThrow(
                () -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在或尚未发布"));
    return response(type);
  }

  /**
   * 字段增量补丁的无损基线（供 MCP {@code update_content_type} 合并与 Diff 使用）。
   *
   * <p>与 {@link ContentTypeResponse#fields()} 同口径过滤结构控件（{@code STRUCTURAL}），但取
   * 存储原始值：{@code validation} 为数据库中的原字符串、{@code fieldConfig} 解析为 JsonNode。
   * 与 {@code FieldInfo.validation}（JSON 解析后的视图）不同，本方法不存在「非 JSON 校验规则
   * 在往返中被改写」的歧义，因此可用于「现有值 + 本次补丁」合成完整请求体。
   */
  public List<CreateContentTypeRequest.FieldDefDto> fieldDefinitions(UUID siteId, UUID id) {
    ContentTypeEntity ct = require(siteId, id);
    return fieldRepo.findByContentTypeIdOrderBySortOrder(ct.getId()).stream()
        .filter(f -> !ContentModelValidator.STRUCTURAL.contains(f.getFieldType()))
        .map(f -> CreateContentTypeRequest.FieldDefDto.fromEntity(f, om))
        .toList();
  }

  public List<ContentTypeResponse> contentMenu(UUID siteId) {
    requireSite(siteId);
    return ctRepo
        .findActiveBySiteIdAndContentMenuEnabledTrueOrderByContentMenuOrderAscNameAsc(siteId)
        .stream().map(this::response).toList();
  }

  public ContentTypeResponse updateContentMenu(UUID siteId, UUID id, boolean enabled,
                                                       String label, Integer order) {
    ContentTypeEntity ct = require(siteId, id);
    ct.setContentMenuEnabled(enabled);
    ct.setContentMenuLabel(label == null || label.isBlank() ? null : label.trim());
    ct.setContentMenuOrder(order == null ? 0 : order);
    return response(ctRepo.save(ct));
  }

  private void requireSite(UUID siteId) {
    if (siteId == null || !siteRepo.existsById(siteId))
      throw new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在，请重新选择或创建站点");
  }

  public ContentTypeResponse publish(UUID siteId, UUID id, String operator, String migrationRules) {
    ContentTypeEntity ct = require(siteId, id);
    if (ContentTypeEntity.STATUS_PUBLISHED.equals(ct.getStatus()))
      throw new BusinessException(ErrorCodes.CONTENT_TYPE_PUBLISHED_READONLY, "模型已发布");
    int from = ct.getPublishedVersion() == null ? 0 : ct.getPublishedVersion();
    ct.setStatus(ContentTypeEntity.STATUS_PUBLISHED);
    ct.setPublishedVersion(ct.getVersion());
    ct.setPublishedAt(Instant.now());
    ct.setPublishedBy(operator);
    ctRepo.save(ct);
    List<UUID> deprecatedIds = deprecateOlderPublishedVersions(siteId, ct);
    migrateContentAfterPublish(siteId, ct, deprecatedIds, migrationRules);
    ContentTypeChangeLogEntity log = new ContentTypeChangeLogEntity();
    log.setContentTypeId(ct.getId());
    log.setFromVersion(from == 0 ? null : from);
    log.setToVersion(ct.getVersion());
    log.setChangeSummary("{\"operation\":\"PUBLISH\"}");
    log.setMigrationRules(migrationRules);
    log.setOperator(operator);
    logRepo.save(log);
    eventPublisher.publishEvent(
        new ContentTypePublishedEvent(siteId, ct.getId(), ct.getPublishedVersion()));
    return response(ct);
  }

  /**
   * 发布后迁移（FR-CM-CT-015 扩展）：把绑定在被废弃旧版本行上的存量内容迁移到新版本。
   * migrationRules 为空/非法 JSON 时不阻断发布（降级为仅重绑），错误记录日志。
   */
  private void migrateContentAfterPublish(UUID siteId, ContentTypeEntity newType,
                                          List<UUID> deprecatedIds, String migrationRules) {
    if (deprecatedIds.isEmpty()) return;
    JsonNode rules = null;
    if (migrationRules != null && !migrationRules.isBlank()) {
      try {
        rules = om.readTree(migrationRules);
        if (rules != null && !rules.isObject()) rules = null;
      } catch (Exception ex) {
        log.warn("migrationRules is not valid JSON, fallback to rebind-only: {}", ex.getMessage());
      }
    }
    for (UUID fromTypeId : deprecatedIds) {
      try {
        var result = migrationService.migrate(siteId, fromTypeId, newType, rules);
        log.info("publish migration: site={} type={} v{} from={} mode={} rebound={} migrated={} snapshots={}",
            siteId, newType.getCode(), newType.getVersion(), fromTypeId,
            result.mode(), result.itemsRebound(), result.itemsMigrated(), result.versionsWritten());
      } catch (Exception ex) {
        // 迁移失败不回滚发布（发布已完成），但记录错误供运维介入
        log.error("publish migration failed: site={} type={} from={} err={}",
            siteId, newType.getCode(), fromTypeId, ex.getMessage(), ex);
      }
    }
  }

  /**
   * 发布即废弃（FR-CM-CT-014 扩展）：同 code 的旧 PUBLISHED 行统一置为 DEPRECATED，
   * 保证一个编码同一时刻只有一个生效版本——避免内容创建双入口与 GraphQL schema 重复定义。
   * 返回被废弃行的 id（供存量内容迁移定位来源）。
   */
  private List<UUID> deprecateOlderPublishedVersions(UUID siteId, ContentTypeEntity current) {
    List<ContentTypeEntity> outdated =
        ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, current.getCode()).stream()
            .filter(v -> !v.getId().equals(current.getId()))
            .filter(v -> ContentTypeEntity.STATUS_PUBLISHED.equals(v.getStatus()))
            .toList();
    for (ContentTypeEntity old : outdated) {
      old.setStatus(ContentTypeEntity.STATUS_DEPRECATED);
      ctRepo.save(old);
      log.info("content type deprecated by newer publish: site={} code={} version={}",
          siteId, old.getCode(), old.getVersion());
    }
    return outdated.stream().map(ContentTypeEntity::getId).toList();
  }

  public void publish(UUID id) {
    ContentTypeEntity ct =
        ctRepo
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCodes.CONTENT_TYPE_NOT_FOUND, "内容模型不存在"));
    publish(ct.getSiteId(), id, "system", null);
  }

  public ContentTypeResponse newVersion(UUID siteId, UUID id) {
    ContentTypeEntity old = require(siteId, id);
    if (!ContentTypeEntity.STATUS_PUBLISHED.equals(old.getStatus()))
      throw new BusinessException(ErrorCodes.CONFLICT, "只能对已发布模型创建新版本");
    ContentTypeEntity ct = new ContentTypeEntity();
    ct.setSiteId(old.getSiteId());
    ct.setCode(old.getCode());
    ct.setName(old.getName());
    ct.setDescription(old.getDescription());
    ct.setTemplateType(old.getTemplateType());
    ct.setDisplayTemplate(old.getDisplayTemplate());
    ct.setIcon(old.getIcon());
    ct.setThumbnail(old.getThumbnail());
    ct.setObjectType(old.getObjectType());
    ct.setControllerPath(old.getControllerPath());
    ct.setFormController(old.getFormController());
    ct.setMergeStrategy(old.getMergeStrategy());
    ct.setParentTypeId(old.getParentTypeId());
    ct.setQuickCreate(old.isQuickCreate());
    ct.setDestinationPattern(old.getDestinationPattern());
    ct.setPreviewImage(old.getPreviewImage());
    ct.setTypeConfig(old.getTypeConfig());
    ct.setFlattenComponents(old.isFlattenComponents());
    ct.setSubtype(old.getSubtype());
    ct.setCopiedFromTypeId(old.getCopiedFromTypeId());
    ct.setFormNotificationEmails(old.getFormNotificationEmails());
    ct.setContentMenuEnabled(old.isContentMenuEnabled());
    ct.setContentMenuLabel(old.getContentMenuLabel());
    ct.setContentMenuOrder(old.getContentMenuOrder());
    ct.setMediaKind(old.getMediaKind());
    ct.setStoragePathPattern(old.getStoragePathPattern());
    ct.setMediaAccept(old.getMediaAccept());
    ct.setMediaMaxFileLength(old.getMediaMaxFileLength());
    ct.setPublishedVersion(old.getPublishedVersion());
    ct.setPublishedAt(old.getPublishedAt());
    ct.setPublishedBy(old.getPublishedBy());
    ct.setVersion(old.getVersion() + 1);
    ct = ctRepo.save(ct);
    copyDefinition(old.getId(), ct.getId());
    copyLifecycle(old.getId(), ct.getId());
    return response(ct);
  }

  public List<ContentTypeResponse> versions(UUID siteId, UUID id) {
    ContentTypeEntity ct = require(siteId, id);
    return ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, ct.getCode()).stream()
        .map(this::response)
        .toList();
  }

  public ContentTypeResponse version(UUID siteId, UUID id, int version) {
    ContentTypeEntity ct = require(siteId, id);
    return response(
        ctRepo
            .findBySiteIdAndCodeAndVersion(siteId, ct.getCode(), version)
            .orElseThrow(
                () -> new BusinessException(ErrorCodes.CONTENT_TYPE_NOT_FOUND, "模型版本不存在")));
  }

  public Map<String, Object> diff(UUID siteId, UUID id, int from, int to) {
    ContentTypeResponse a = version(siteId, id, from), b = version(siteId, id, to);
    Set<String> ac = new LinkedHashSet<>(), bc = new LinkedHashSet<>();
    a.fields().forEach(f -> ac.add(f.code()));
    b.fields().forEach(f -> bc.add(f.code()));
    Set<String> added = new LinkedHashSet<>(bc);
    added.removeAll(ac);
    Set<String> removed = new LinkedHashSet<>(ac);
    removed.removeAll(bc);
    return Map.of("from", from, "to", to, "added", added, "removed", removed, "changed", List.of());
  }

  public List<Map<String, Object>> references(UUID siteId, UUID id) {
    require(siteId, id);
    return itemRepo.findByContentTypeId(id).stream()
        .map(x -> Map.<String, Object>of("type", "CONTENT", "id", x.getId(), "title", x.getTitle()))
        .toList();
  }

  public List<Map<String, Object>> fieldTypes() {
    return ContentModelValidator.TYPES.stream()
        .sorted()
        .map(
            t ->
                Map.<String, Object>of(
                    "code",
                    t,
                    "structural",
                    ContentModelValidator.STRUCTURAL.contains(t),
                    "category",
                    category(t)))
        .toList();
  }

  private static String category(String type) {
    if (ContentModelValidator.STRUCTURAL.contains(type)) return "结构控件";
    if (Set.of("TAG", "CATEGORY", "HIDDEN", "SITE_FOLDER", "RELATIONSHIP", "CONSTANT",
        "CUSTOM_CODE", "BLOCK_EDITOR").contains(type)) return "补充字段";
    if (Set.of("CONTENT_REF", "MEDIA_REF", "TAXONOMY_REF", "COMPONENT_REF", "KEY_VALUE", "JSON",
        "COLOR").contains(type)) return "引用字段";
    return "基础字段";
  }

  public Map<String, Object> preview(UUID siteId, UUID id, JsonNode data) {
    ContentTypeResponse ct = get(siteId, id);
    List<Map<String, String>> errors = new ArrayList<>();
    for (var f : ct.fields())
      if (f.required()
          && (data == null || !data.hasNonNull(f.code()) || data.get(f.code()).asText().isBlank()))
        errors.add(Map.of("field", f.code(), "reason", "必填字段不能为空"));
    return Map.of("valid", errors.isEmpty(), "errors", errors);
  }

  public void delete(UUID siteId, UUID id) {
    ContentTypeEntity ct = require(siteId, id);
    if (ct.isSystem()) {
      throw new BusinessException(ErrorCodes.CONFLICT, "系统内容类型不可删除（spec 5.2/8.9）");
    }
    if (!itemRepo.findByContentTypeId(id).isEmpty())
      throw new BusinessException(
          ErrorCodes.CONTENT_TYPE_REFERENCED, "Content type is used by content and cannot be deleted");
    if (!ctRepo.findByParentTypeId(id).isEmpty())
      throw new BusinessException(
          ErrorCodes.CONTENT_TYPE_REFERENCED,
          "Content type has inheritance children and cannot be deleted");
    lifecycleRepo.deleteByContentTypeId(id);
    lifecycleRepo.flush();
    logRepo.deleteByContentTypeId(id);
    logRepo.flush();
    layoutRepo.deleteByContentTypeId(id);
    deleteFields(id);
    ctRepo.delete(ct);
  }

  public List<Map<String, Object>> parentCandidates(UUID siteId, UUID id) {
    require(siteId, id);
    Set<UUID> excluded = descendants(id);
    excluded.add(id);
    return ctRepo.findBySiteIdOrderByCodeAsc(siteId).stream().filter(x -> !excluded.contains(x.getId()))
        .map(x -> Map.<String, Object>of("id", x.getId(), "code", x.getCode(), "name", x.getName(), "objectType", x.getObjectType())).toList();
  }

  public List<ContentTypeLifecycleEntity> lifecycle(UUID siteId, UUID id) {
    require(siteId, id);
    return lifecycleRepo.findByContentTypeIdOrderByEvent(id);
  }

  public ContentTypeLifecycleEntity saveLifecycle(UUID siteId, UUID id, String event, JsonNode body, String operator) {
    require(siteId, id);
    String normalized = event.toUpperCase(Locale.ROOT);
    if (!Set.of("CREATE", "UPDATE", "COPY", "DELETE", "WEBHOOK").contains(normalized))
      throw new BusinessException(ErrorCodes.LIFECYCLE_SCRIPT_INVALID, "生命周期事件非法");
    String script = body.path("scriptContent").asText("");
    validateLifecycleScript(script);
    ContentTypeLifecycleEntity hook = lifecycleRepo.findByContentTypeIdAndEvent(id, normalized).orElseGet(ContentTypeLifecycleEntity::new);
    hook.setContentTypeId(id); hook.setEvent(normalized); hook.setScriptContent(script);
    hook.setScriptLang(body.path("scriptLang").asText("GROOVY"));
    hook.setTimeoutMs(body.path("timeoutMs").asInt(5000)); hook.setEnabled(body.path("enabled").asBoolean(true));
    // WEBHOOK 事件监听白名单（逗号分隔，空 = 监听全部 Knox webhook 事件，FR-CM-MEDIA-012）
    if (body.hasNonNull("listenEvents")) {
        hook.setListenEvents(body.path("listenEvents").asText(null));
    }
    if (hook.getCreatedBy() == null) hook.setCreatedBy(operator);
    return lifecycleRepo.save(hook);
  }

  public void deleteLifecycle(UUID siteId, UUID id, String event) {
    require(siteId, id);
    lifecycleRepo.findByContentTypeIdAndEvent(id, event.toUpperCase(Locale.ROOT)).ifPresent(lifecycleRepo::delete);
  }

  public Map<String, Object> dryRunLifecycle(UUID siteId, UUID id, String event, JsonNode body) {
    require(siteId, id);
    String script = body.has("scriptContent") ? body.path("scriptContent").asText() : lifecycleRepo
        .findByContentTypeIdAndEvent(id, event.toUpperCase(Locale.ROOT)).map(ContentTypeLifecycleEntity::getScriptContent).orElse("");
    validateLifecycleScript(script);
    return Map.of("valid", true, "executed", false, "mode", "VALIDATION_ONLY", "event", event.toUpperCase(Locale.ROOT),
        "message", "Static validation passed. The script was not executed and no data was persisted.");
  }

  public List<ContentTypeResponse> quickCreate(UUID siteId) {
    return ctRepo.findActiveBySiteIdAndQuickCreateTrueOrderByNameAsc(siteId).stream()
        .map(this::response)
        .toList();
  }

  /** 媒体内容类型列表（预置 + 自定义，objectType=MEDIA，FR-CM-MEDIA-002~006）。 */
  public List<ContentTypeResponse> mediaTypes(UUID siteId) {
    requireSite(siteId);
    return ctRepo.findBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA").stream()
        .map(this::response)
        .toList();
  }

  /** 媒体类型级配置更新（accept/maxFileLength/storagePathPattern，FR-CM-MEDIA-040/050）。 */
  public ContentTypeResponse updateMediaConfig(UUID siteId, UUID id, String accept,
                                               Long maxFileLength, String storagePathPattern) {
    ContentTypeEntity ct = require(siteId, id);
    if (!"MEDIA".equals(ct.getObjectType())) {
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "仅 MEDIA 内容类型支持媒体配置");
    }
    if (ContentTypeEntity.STATUS_PUBLISHED.equals(ct.getStatus())) {
      throw new BusinessException(ErrorCodes.CONTENT_TYPE_PUBLISHED_READONLY, "已发布版本不可原地修改");
    }
    MediaClassifyService.validateAccept(accept);
    MediaClassifyService.validateStoragePattern(storagePathPattern);
    if (maxFileLength != null && maxFileLength <= 0) {
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "maxFileLength 必须为正数");
    }
    ct.setMediaAccept(accept);
    ct.setMediaMaxFileLength(maxFileLength);
    ct.setStoragePathPattern(storagePathPattern);
    return response(ctRepo.save(ct));
  }

  public Map<String, Object> previewDestination(String pattern) {
    if (pattern == null || pattern.isBlank()) throw new BusinessException(ErrorCodes.QUICK_CREATE_DESTINATION_REQUIRED, "目标路径模式不能为空");
    java.time.LocalDate date = java.time.LocalDate.now();
    String objectId = UUID.nameUUIDFromBytes(pattern.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    String path = pattern.replace("{objectId}", objectId).replace("{year}", String.valueOf(date.getYear()))
        .replace("{yyyy}", String.valueOf(date.getYear())).replace("{month}", String.format("%02d", date.getMonthValue()))
        .replace("{mm}", String.format("%02d", date.getMonthValue())).replace("{dd}", String.format("%02d", date.getDayOfMonth()));
    if (path.matches(".*\\{[^}]+}.*")) throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "目标路径包含不支持的宏");
    return Map.of("pattern", pattern, "path", path, "deterministicPreview", true);
  }

  public Map<String, String> suggestVariableName(String name, String fieldType) {
    String[] words = (name == null ? "field" : name).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split("\\s+");
    StringBuilder base = new StringBuilder(words.length == 0 || words[0].isBlank() ? "field" : words[0].toLowerCase(Locale.ROOT));
    for (int i = 1; i < words.length; i++) if (!words[i].isBlank()) base.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
    String value = base.toString().replaceAll("[^a-zA-Z0-9]", "");
    if (value.isBlank() || !Character.isLowerCase(value.charAt(0))) value = "field" + (value.isBlank() ? "" : value);
    String suffix = switch (fieldType == null ? "STRING" : fieldType) { case "LONG_TEXT" -> "_t"; case "RICH_TEXT" -> "_html"; case "INTEGER" -> "_i"; case "DECIMAL" -> "_d"; case "BOOLEAN" -> "_b"; case "DATE_TIME" -> "_dt"; case "MULTI_ENUM", "KEY_VALUE" -> "_ss"; default -> "_s"; };
    return Map.of("variableName", value + suffix);
  }

  public Set<String> reservedVariableNames() { return ContentModelValidator.RESERVED; }

  /** 内容类型复制（FR-CM-COPY-001~010）：以现有类型为模板复制新草稿类型。 */
  public ContentTypeResponse copy(UUID siteId, UUID id, String name, String code, String icon) {
    ContentTypeEntity src = require(siteId, id);
    if (src.isSystem()) {
      throw new BusinessException(ErrorCodes.CONFLICT, "系统内容类型不可复制（spec 5.2/8.9）");
    }
    String targetCode = code == null || code.isBlank() ? src.getCode() + "_copy" : code.trim();
    if (ctRepo.existsBySiteIdAndCode(siteId, targetCode))
      throw new BusinessException(ErrorCodes.CONTENT_TYPE_CODE_DUPLICATE, "内容模型编码已存在");
    ContentTypeEntity ct = new ContentTypeEntity();
    ct.setSiteId(siteId);
    ct.setCode(targetCode);
    ct.setName(name == null || name.isBlank() ? src.getName() + "（副本）" : name);
    ct.setDescription(src.getDescription());
    ct.setTemplateType(src.getTemplateType());
    ct.setDisplayTemplate(src.getDisplayTemplate());
    ct.setIcon(icon == null ? src.getIcon() : icon);
    ct.setThumbnail(src.getThumbnail());
    ct.setObjectType(src.getObjectType());
    ct.setMergeStrategy(src.getMergeStrategy());
    ct.setFlattenComponents(src.isFlattenComponents());
    ct.setSubtype(src.getSubtype());
    ct.setMediaKind(src.getMediaKind());
    ct.setStoragePathPattern(src.getStoragePathPattern());
    ct.setMediaAccept(src.getMediaAccept());
    ct.setMediaMaxFileLength(src.getMediaMaxFileLength());
    ct.setCopiedFromTypeId(src.getId());
    ct.setVersion(1);
    ct.setStatus(ContentTypeEntity.STATUS_DRAFT);
    ct = ctRepo.save(ct);
    copyDefinition(src.getId(), ct.getId());
    return response(ct);
  }

  public JsonNode fieldVariables(UUID siteId, UUID id, UUID fieldId) {
    require(siteId, id);
    return variablesOf(requireField(id, id)).deepCopy();
  }

  public FieldDefinitionEntity saveFieldVariables(
      UUID siteId, UUID id, UUID fieldId, JsonNode variables) {
    ContentTypeEntity ct = require(siteId, id);
    if (ContentTypeEntity.STATUS_PUBLISHED.equals(ct.getStatus()))
      throw new BusinessException(
          ErrorCodes.CONTENT_TYPE_PUBLISHED_READONLY, "已发布版本不可原地修改");
    FieldDefinitionEntity f = requireField(id, fieldId);
    if (variables != null && !variables.isObject())
      throw new BusinessException(ErrorCodes.FIELD_VARIABLE_KEY_INVALID, "字段变量必须是对象");
    ObjectNode cfg = fieldConfigObject(f);
    if (variables == null || variables.isNull()) cfg.remove("fieldVariables");
    else cfg.set("fieldVariables", variables);
    f.setFieldConfig(cfg.toString());
    return fieldRepo.save(f);
  }

  public FieldDefinitionEntity deleteFieldVariable(UUID siteId, UUID id, UUID fieldId, String key) {
    ContentTypeEntity ct = require(siteId, id);
    if (ContentTypeEntity.STATUS_PUBLISHED.equals(ct.getStatus()))
      throw new BusinessException(
          ErrorCodes.CONTENT_TYPE_PUBLISHED_READONLY, "已发布版本不可原地修改");
    FieldDefinitionEntity f = requireField(id, fieldId);
    ObjectNode cfg = fieldConfigObject(f);
    JsonNode vars = cfg.get("fieldVariables");
    if (vars != null && vars.isObject()) {
      ((ObjectNode) vars).remove(key);
      if (vars.isEmpty()) cfg.remove("fieldVariables");
      else cfg.set("fieldVariables", vars);
    }
    f.setFieldConfig(cfg.toString());
    return fieldRepo.save(f);
  }

  /** CUSTOM_CODE 字段渲染（FR-CM-FTSUP-008）：执行 renderCode 返回渲染结果。 */
  public ScriptSandboxService.ScriptResult renderCustomCode(
      UUID siteId, UUID id, UUID fieldId, JsonNode input) {
    FieldDefinitionEntity f = requireField(id, fieldId);
    if (!"CUSTOM_CODE".equals(f.getFieldType()))
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "字段类型不是 CUSTOM_CODE");
    JsonNode cfg = parseJson(f.getFieldConfig());
    String code = cfg == null ? null : cfg.path("renderCode").asText(null);
    String lang = cfg == null ? "JS" : cfg.path("codeLang").asText("JS");
    if (code == null || code.isBlank())
      throw new BusinessException(ErrorCodes.CUSTOM_CODE_COMPILE_ERROR, "字段未配置渲染代码");
    return sandbox.render(code, lang, input);
  }

  /** CUSTOM_CODE 字段校验（FR-CM-FTSUP-008）：执行 validationCode 返回校验结果。 */
  public ScriptSandboxService.ScriptResult validateCustomCode(
      UUID siteId, UUID id, UUID fieldId, JsonNode input) {
    FieldDefinitionEntity f = requireField(id, fieldId);
    if (!"CUSTOM_CODE".equals(f.getFieldType()))
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "字段类型不是 CUSTOM_CODE");
    JsonNode cfg = parseJson(f.getFieldConfig());
    String code = cfg == null ? null : cfg.path("validationCode").asText(null);
    String lang = cfg == null ? "JS" : cfg.path("codeLang").asText("JS");
    if (code == null || code.isBlank()) return new ScriptSandboxService.ScriptResult(true, false, null, List.of(), "未配置校验代码");
    return sandbox.validate(code, lang, input);
  }

  private FieldDefinitionEntity requireField(UUID contentTypeId, UUID fieldId) {
    FieldDefinitionEntity f =
        fieldRepo
            .findById(fieldId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.FIELD_NOT_FOUND, "字段不存在"));
    if (!contentTypeId.equals(f.getContentTypeId()))
      throw new BusinessException(ErrorCodes.FIELD_NOT_FOUND, "字段不存在");
    return f;
  }

  private JsonNode variablesOf(FieldDefinitionEntity f) {
    JsonNode cfg = parseJson(f.getFieldConfig());
    JsonNode vars = cfg != null && cfg.isObject() ? cfg.get("fieldVariables") : null;
    return vars == null
        ? om.getNodeFactory().objectNode()
        : vars;
  }

  private ObjectNode fieldConfigObject(FieldDefinitionEntity f) {
    JsonNode cfg = parseJson(f.getFieldConfig());
    if (cfg != null && cfg.isObject()) return (ObjectNode) cfg;
    return om.createObjectNode();
  }

  private JsonNode parseJson(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return om.readTree(s);
    } catch (Exception e) {
      return om.getNodeFactory().objectNode();
    }
  }

  private void validateInheritance(UUID siteId, UUID id, UUID parentId) {
    if (parentId == null) return;
    ContentTypeEntity parent = require(siteId, parentId);
    Set<UUID> seen = new HashSet<>(); seen.add(id);
    while (parent != null) {
      if (!seen.add(parent.getId())) throw new BusinessException(ErrorCodes.INHERIT_CYCLE_DETECTED, "检测到内容类型继承环");
      parent = parent.getParentTypeId() == null ? null : ctRepo.findById(parent.getParentTypeId()).orElseThrow(() -> new BusinessException(ErrorCodes.PARENT_TYPE_NOT_FOUND, "父类型不存在"));
    }
  }

  private Set<UUID> descendants(UUID id) {
    Set<UUID> result = new HashSet<>(); ArrayDeque<UUID> queue = new ArrayDeque<>(); queue.add(id);
    while (!queue.isEmpty()) for (ContentTypeEntity child : ctRepo.findByParentTypeId(queue.remove())) if (result.add(child.getId())) queue.add(child.getId());
    return result;
  }

  private void validateLifecycleScript(String script) {
    if (script == null || script.isBlank()) throw new BusinessException(ErrorCodes.LIFECYCLE_SCRIPT_INVALID, "脚本内容不能为空");
    String lower = script.toLowerCase(Locale.ROOT);
    for (String token : List.of("system.exit", "runtime.getruntime", "processbuilder", "java.io", "java.nio.file", "socket", "urlconnection", "classloader", "@grapes", "evaluate(", "executemethod"))
      if (lower.contains(token)) throw new BusinessException(ErrorCodes.LIFECYCLE_SCRIPT_INVALID, "脚本包含禁止标记: " + token);
    int braces = 0; for (char c : script.toCharArray()) { if (c == '{') braces++; else if (c == '}' && --braces < 0) break; }
    if (braces != 0) throw new BusinessException(ErrorCodes.LIFECYCLE_SCRIPT_INVALID, "脚本花括号不平衡");
  }

  private void copyLifecycle(UUID from, UUID to) {
    for (ContentTypeLifecycleEntity old : lifecycleRepo.findByContentTypeIdOrderByEvent(from)) {
      ContentTypeLifecycleEntity copy = new ContentTypeLifecycleEntity(); copy.setContentTypeId(to); copy.setEvent(old.getEvent());
      copy.setScriptContent(old.getScriptContent()); copy.setScriptLang(old.getScriptLang()); copy.setTimeoutMs(old.getTimeoutMs());
      copy.setEnabled(old.isEnabled()); copy.setCreatedBy(old.getCreatedBy()); lifecycleRepo.save(copy);
    }
  }
  private void validateReferences(
      UUID siteId, List<CreateContentTypeRequest.LayoutElementDto> nodes) {
    for (var n : nodes) {
      var f = n.field();
      if (f != null
          && (f.fieldType().equals("CONTENT_REF") || f.fieldType().equals("COMPONENT_REF"))
          && f.fieldConfig() != null) {
        String key =
            f.fieldType().equals("CONTENT_REF") ? "allowedContentTypes" : "allowedComponentTypes";
        JsonNode refs = f.fieldConfig().get(key);
        if (refs != null && refs.isArray())
          for (JsonNode ref : refs)
            if (!ctRepo.existsBySiteIdAndCode(siteId, ref.asText()))
              throw new BusinessException(
                  ErrorCodes.REF_CONTENT_TYPE_NOT_FOUND,
                  "引用的内容类型不存在或不属于当前站点: " + ref.asText(),
                  List.of(new ApiError.FieldError("fieldConfig." + key, ref.asText())));
      }
      if (f != null && f.fieldType().equals("RELATIONSHIP") && f.fieldConfig() != null) {
        JsonNode relationId = f.fieldConfig().get("relationId");
        if (relationId == null || relationId.isNull() || relationId.asText().isBlank()) continue;
        try {
          UUID rid = UUID.fromString(relationId.asText());
          if (!relationRepo.existsById(rid))
            throw new BusinessException(
                ErrorCodes.RELATION_NOT_FOUND,
                "关系定义不存在: " + rid,
                List.of(new ApiError.FieldError("fieldConfig.relationId", relationId.asText())));
        } catch (IllegalArgumentException ex) {
          throw new BusinessException(
              ErrorCodes.RELATION_NOT_FOUND,
              "关系定义 ID 非法: " + relationId.asText(),
              List.of(new ApiError.FieldError("fieldConfig.relationId", relationId.asText())));
        }
      }
      if (f != null && f.fieldType().equals("MEDIA_REF") && f.fieldConfig() != null) {
        JsonNode refs = f.fieldConfig().get("allowedContentTypes");
        if (refs != null && refs.isArray())
          for (JsonNode ref : refs)
            if (!ctRepo.existsBySiteIdAndCode(siteId, ref.asText()))
              throw new BusinessException(
                  ErrorCodes.REF_CONTENT_TYPE_NOT_FOUND,
                  "引用的媒体内容类型不存在或不属于当前站点: " + ref.asText(),
                  List.of(new ApiError.FieldError("fieldConfig.allowedContentTypes", ref.asText())));
      }
      validateReferences(siteId, n.children() == null ? List.of() : n.children());
    }
  }

  private void apply(ContentTypeEntity ct, CreateContentTypeRequest r) {
    ct.setName(r.name());
    ct.setDescription(r.description());
    ct.setTemplateType(r.templateType() == null ? "PAGE" : r.templateType());
    ct.setDisplayTemplate(r.displayTemplate());
    ct.setIcon(r.icon());
    ct.setThumbnail(r.thumbnail());
    ct.setObjectType(r.objectType() == null ? defaultObjectType(ct.getTemplateType()) : r.objectType());
    ct.setControllerPath(r.controllerPath());
    ct.setFormController(r.formController());
    ct.setMergeStrategy(r.mergeStrategy() == null ? "NONE" : r.mergeStrategy());
    ct.setParentTypeId(r.parentTypeId());
    ct.setQuickCreate(r.quickCreate());
    ct.setDestinationPattern(r.destinationPattern());
    ct.setPreviewImage(r.previewImage());
    ct.setTypeConfig(json(r.config()));
    ct.setFlattenComponents(r.flattenComponents() == null || r.flattenComponents());
    ct.setSubtype(r.subtype());
    ct.setFormNotificationEmails(r.formNotificationEmails());
    ct.setMediaKind(r.mediaKind());
    if (r.schemaOrgType() != null) {
      String v = r.schemaOrgType().trim();
      ct.setSchemaOrgType(v.isBlank() ? "Article" : v);
    }
  }

  private ContentTypeEntity require(UUID siteId, UUID id) {
    ContentTypeEntity ct =
        ctRepo
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCodes.CONTENT_TYPE_NOT_FOUND, "内容模型不存在"));
    if (!ct.getSiteId().equals(siteId))
      throw new BusinessException(ErrorCodes.CONTENT_TYPE_NOT_FOUND, "内容模型不存在");
    return ct;
  }

  private static String defaultObjectType(String templateType) {
    if ("MEDIA".equals(templateType)) return "MEDIA";
    return "COMPONENT".equals(templateType) ? "COMPONENT" : "PAGE";
  }

  private ContentTypeResponse response(ContentTypeEntity ct) {
    return ContentTypeResponse.from(
        ct,
        fieldRepo.findByContentTypeIdOrderBySortOrder(ct.getId()),
        layoutRepo.findByContentTypeIdOrderBySortOrder(ct.getId()),
        om);
  }

  private void saveDefinition(
      ContentTypeEntity ct, List<CreateContentTypeRequest.LayoutElementDto> roots) {
    saveNodes(ct.getId(), null, roots);
  }

  private void saveNodes(
      UUID ctId, UUID parent, List<CreateContentTypeRequest.LayoutElementDto> nodes) {
    int pos = 0;
    for (var n : nodes) {
      UUID id = n.elementId() == null ? UUID.randomUUID() : n.elementId();
      var dto = n.field();
      FieldDefinitionEntity f = new FieldDefinitionEntity();
      f.setId(id);
      f.setContentTypeId(ctId);
      f.setParentId(parent);
      f.setFieldType(dto == null ? n.elementType() : dto.fieldType());
      f.setCode(
          dto == null
              ? text(n.config(), "variableName", "element_" + id.toString().substring(0, 8))
              : dto.code());
      f.setName(dto == null ? text(n.config(), "title", f.getCode()) : dto.name());
      f.setSortOrder(n.sortOrder() > 0 ? n.sortOrder() : (++pos) * 10);
      if (dto != null) {
        f.setRequired(dto.required());
        f.setGroupName(dto.groupName());
        f.setHelpText(dto.helpText());
        f.setDefaultValue(dto.defaultValue());
        f.setReadOnly(dto.readOnly());
        f.setValidation(json(dto.validation()));
        f.setFieldConfig(json(dto.fieldConfig()));
        f.setConditionExpr(dto.conditionExpr());
        f.setI18n(dto.i18n());
        f.setAdminIndex(dto.adminIndex());
        f.setPublicIndex(dto.publicIndex());
        f.setSortable(dto.sortable());
        f.setFilterable(dto.filterable());
        f.setHighlight(dto.highlight());
        f.setUniqueField(dto.uniqueField());
      } else {
        f.setFieldConfig(json(n.config()));
        f.setConditionExpr(text(n.config(), "conditionExpr", null));
      }
      fieldRepo.save(f);
      FormLayoutEntity l = new FormLayoutEntity();
      l.setContentTypeId(ctId);
      l.setElementId(id);
      l.setElementType(dto == null ? n.elementType() : "FIELD");
      l.setParentId(parent);
      l.setSortOrder(f.getSortOrder());
      layoutRepo.save(l);
      saveNodes(ctId, id, n.children() == null ? List.of() : n.children());
    }
  }

  private void seedSystemFields(ContentTypeEntity ct) {
    List<ContentModelValidator.SystemField> required =
        ContentModelValidator.systemFields(ct.getObjectType(), ct.getMediaKind());
    List<FieldDefinitionEntity> existing = fieldRepo.findByContentTypeIdOrderBySortOrder(ct.getId());
    Map<String, FieldDefinitionEntity> byCode = new HashMap<>();
    existing.forEach(f -> byCode.put(f.getCode(), f));
    int base = existing.stream().mapToInt(FieldDefinitionEntity::getSortOrder).max().orElse(0);
    int pos = 0;
    for (ContentModelValidator.SystemField sf : required) {
      FieldDefinitionEntity f = byCode.get(sf.code());
      if (f == null) {
        f = new FieldDefinitionEntity();
        f.setId(UUID.randomUUID());
        f.setContentTypeId(ct.getId());
        f.setCode(sf.code());
        f.setName(sf.name());
        f.setFieldType(sf.fieldType());
        f.setRequired(false);
        f.setSortOrder(base + (++pos) * 10);
        f.setGroupName("system");
        f.setI18n(false);
        f.setAdminIndex(true);
        f.setPublicIndex(false);
        f.setSortable(false);
        f.setFilterable(false);
        f.setHighlight(false);
        f.setReadOnly(false);
        f.setSystem(true);
        f.setSystemFieldType(sf.systemFieldType());
        f.setUniqueField(false);
        fieldRepo.save(f);
      } else {
        f.setSystem(true);
        f.setSystemFieldType(sf.systemFieldType());
        fieldRepo.save(f);
      }
      attachSystemFieldToLayout(ct.getId(), f);
    }
  }

  /**
   * 把系统字段挂载到内容类型布局的根 FORM_SECTION 下（幂等）。
   *
   * <p>系统字段必须同时出现在 field_definition 与 form_layout 中，否则设计器按布局渲染时
   * 只显示一个空分区（FR-CM-MEDIA-003 的系统字段对用户不可见）。若类型尚无任何布局分区，
   * 则在根层级直接挂载，保证字段至少可达。
   */
  private void attachSystemFieldToLayout(UUID contentTypeId, FieldDefinitionEntity field) {
    List<FormLayoutEntity> layouts =
        layoutRepo.findByContentTypeIdOrderBySortOrder(contentTypeId);
    boolean already =
        layouts.stream().anyMatch(l -> l.getElementId().equals(field.getId()));
    if (already) return;
    UUID sectionElementId =
        layouts.stream()
            .filter(l -> "FORM_SECTION".equals(l.getElementType()) && l.getParentId() == null)
            .map(FormLayoutEntity::getElementId)
            .findFirst()
            .orElse(null);
    FormLayoutEntity l = new FormLayoutEntity();
    l.setContentTypeId(contentTypeId);
    l.setElementId(field.getId());
    l.setElementType("FIELD");
    l.setParentId(sectionElementId);
    l.setSortOrder(field.getSortOrder());
    layoutRepo.save(l);
  }

  private void deleteFields(UUID contentTypeId) {
    List<FieldDefinitionEntity> fields =
        fieldRepo.findByContentTypeIdOrderBySortOrder(contentTypeId);
    Map<UUID, FieldDefinitionEntity> byId = new HashMap<>();
    fields.forEach(field -> byId.put(field.getId(), field));
    fields.sort(
        Comparator.comparingInt((FieldDefinitionEntity field) -> depth(field, byId)).reversed());
    fieldRepo.deleteAll(fields);
    fieldRepo.flush();
  }

  private static int depth(FieldDefinitionEntity field, Map<UUID, FieldDefinitionEntity> fields) {
    int depth = 0;
    UUID parent = field.getParentId();
    Set<UUID> visited = new HashSet<>();
    while (parent != null && fields.containsKey(parent) && visited.add(parent)) {
      depth++;
      parent = fields.get(parent).getParentId();
    }
    return depth;
  }

  private void copyDefinition(UUID from, UUID to) {
    Map<UUID, UUID> ids = new HashMap<>();
    for (FieldDefinitionEntity old : fieldRepo.findByContentTypeIdOrderBySortOrder(from))
      ids.put(old.getId(), UUID.randomUUID());
    List<FieldDefinitionEntity> source = fieldRepo.findByContentTypeIdOrderBySortOrder(from);
    for (FieldDefinitionEntity old : source) {
      FieldDefinitionEntity f = new FieldDefinitionEntity();
      f.setId(ids.get(old.getId()));
      f.setContentTypeId(to);
      f.setParentId(null);
      f.setCode(old.getCode());
      f.setName(old.getName());
      f.setFieldType(old.getFieldType());
      f.setRequired(old.isRequired());
      f.setSortOrder(old.getSortOrder());
      f.setGroupName(old.getGroupName());
      f.setHelpText(old.getHelpText());
      f.setDefaultValue(old.getDefaultValue());
      f.setReadOnly(old.isReadOnly());
      f.setValidation(old.getValidation());
      f.setFieldConfig(old.getFieldConfig());
      f.setConditionExpr(old.getConditionExpr());
      f.setI18n(old.isI18n());
      f.setAdminIndex(old.isAdminIndex());
      f.setPublicIndex(old.isPublicIndex());
      f.setSortable(old.isSortable());
      f.setFilterable(old.isFilterable());
      f.setHighlight(old.isHighlight());
      f.setSystem(old.isSystem());
      f.setSystemFieldType(old.getSystemFieldType());
      f.setUniqueField(old.isUniqueField());
      fieldRepo.save(f);
    }
    for (FieldDefinitionEntity old : source) {
      fieldRepo
          .findById(ids.get(old.getId()))
          .ifPresent(
              f -> {
                f.setParentId(ids.get(old.getParentId()));
                fieldRepo.save(f);
              });
    }
    for (FormLayoutEntity old : layoutRepo.findByContentTypeIdOrderBySortOrder(from)) {
      FormLayoutEntity l = new FormLayoutEntity();
      l.setContentTypeId(to);
      l.setElementId(ids.get(old.getElementId()));
      l.setParentId(ids.get(old.getParentId()));
      l.setElementType(old.getElementType());
      l.setSortOrder(old.getSortOrder());
      layoutRepo.save(l);
    }
  }

  private String json(Object v) {
    if (v == null) return null;
    try {
      return v instanceof String s ? s : om.writeValueAsString(v);
    } catch (Exception e) {
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "JSON 配置非法");
    }
  }

  private static String text(JsonNode n, String k, String d) {
    JsonNode v = n == null ? null : n.get(k);
    return v == null || v.isNull() ? d : v.asText();
  }
}
