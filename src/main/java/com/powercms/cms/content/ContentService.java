package com.powercms.cms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.compose.ClassComposeService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.outbox.OutboxService;
import com.powercms.cms.sitefolder.entity.SiteFolderEntity;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import com.powercms.common.error.ApiError;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ContentService {

    private final ContentItemRepository itemRepo;
    private final CategoryRepository categoryRepo;
    private final ContentVersionRepository versionRepo;
    private final ContentTypeRepository ctRepo;
    private final FieldDefinitionRepository fieldRepo;
    private final SiteFolderRepository folderRepo;
    private final ClassComposeService classCompose;
    private final com.powercms.cms.sitepackage.PageCompositionService compositionService;
    private final ObjectMapper om;
    private final OutboxService outbox;
    private final ApplicationEventPublisher events;
    private final ObjectPermissionService objectPermissions;
    private final AuditService audit;
    private final AiDirectPublishGate aiDirectPublishGate;
    private final com.powercms.cms.identity.ActorNameResolver actorNames;
    private final com.powercms.cms.delivery.PageRenderService pageRenderService;

    /**
     * DEF-M12-014：状态推进到 IN_REVIEW 需要同步生成审核待办，但 {@code WorkflowService} 构造期
     * 已依赖本服务，直接构造注入会成环 —— 故按 {@code ObjectProvider} 延迟解析。
     * 单测直构 {@code new ContentService(...)} 时该字段为 null，此时跳过任务生成（状态流转本身不受影响）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<ReviewTaskPort> workflow;

    /**
     * 列表预览图解析（内容搜索/页面管理的缩略图与列表视图）。单测直构 {@code new ContentService(...)}
     * 时为 null，此时列表不下发预览图，界面回退默认图片。
     */
    private ContentPreviewImageService previewImages;

    @Autowired(required = false)
    public void setPreviewImages(ContentPreviewImageService previewImages) {
        this.previewImages = previewImages;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContentService.class);

    ContentService(ContentItemRepository itemRepo, CategoryRepository categoryRepo, ContentVersionRepository versionRepo,
                   ContentTypeRepository ctRepo, FieldDefinitionRepository fieldRepo,
                   SiteFolderRepository folderRepo,
                   ClassComposeService classCompose,
                   com.powercms.cms.sitepackage.PageCompositionService compositionService,
                   ObjectMapper om, OutboxService outbox, ApplicationEventPublisher events,
                   ObjectPermissionService objectPermissions, AuditService audit,
                   AiDirectPublishGate aiDirectPublishGate,
                   com.powercms.cms.identity.ActorNameResolver actorNames,
                   com.powercms.cms.delivery.PageRenderService pageRenderService) {
        this.itemRepo = itemRepo;
        this.categoryRepo = categoryRepo;
        this.versionRepo = versionRepo;
        this.ctRepo = ctRepo;
        this.fieldRepo = fieldRepo;
        this.folderRepo = folderRepo;
        this.classCompose = classCompose;
        this.compositionService = compositionService;
        this.om = om;
        this.outbox = outbox;
        this.events = events;
        this.objectPermissions = objectPermissions;
        this.audit = audit;
        this.aiDirectPublishGate = aiDirectPublishGate;
        this.actorNames = actorNames;
        this.pageRenderService = pageRenderService;
    }

    public record ContentInfo(UUID id, UUID siteId, UUID categoryId, UUID folderId, UUID contentTypeId,
                              String title, String status, int currentVersion, String routePath,
                              String templatePath, String objectType, String createdBy,
                              java.time.Instant createdAt,
                              boolean aiGenerated, String aiModel, Integer aiPromptVersion,
                              java.time.Instant aiGeneratedAt, String createdByName, String previewImage) {
        /**
         * 兼容旧签名（OBS-11-04 之前构造 {@code ContentInfo} 的调用方/用例）：不解析展示名，
         * 界面按 {@code createdByName || createdBy} 回退。
         */
        public ContentInfo(UUID id, UUID siteId, UUID categoryId, UUID folderId, UUID contentTypeId,
                           String title, String status, int currentVersion, String routePath,
                           String templatePath, String objectType, String createdBy,
                           java.time.Instant createdAt,
                           boolean aiGenerated, String aiModel, Integer aiPromptVersion,
                           java.time.Instant aiGeneratedAt) {
            this(id, siteId, categoryId, folderId, contentTypeId, title, status, currentVersion,
                routePath, templatePath, objectType, createdBy, createdAt, aiGenerated, aiModel,
                aiPromptVersion, aiGeneratedAt, null, null);
        }

        /** 兼容加入预览图（OBS-11-04 与列表预览图之间）的构造签名。 */
        public ContentInfo(UUID id, UUID siteId, UUID categoryId, UUID folderId, UUID contentTypeId,
                           String title, String status, int currentVersion, String routePath,
                           String templatePath, String objectType, String createdBy,
                           java.time.Instant createdAt,
                           boolean aiGenerated, String aiModel, Integer aiPromptVersion,
                           java.time.Instant aiGeneratedAt, String createdByName) {
            this(id, siteId, categoryId, folderId, contentTypeId, title, status, currentVersion,
                routePath, templatePath, objectType, createdBy, createdAt, aiGenerated, aiModel,
                aiPromptVersion, aiGeneratedAt, createdByName, null);
        }

        /** OBS-11-04：换用解析后的展示名，其余字段不变。 */
        ContentInfo withCreatedByName(String createdByName) {
            return new ContentInfo(id, siteId, categoryId, folderId, contentTypeId, title, status,
                currentVersion, routePath, templatePath, objectType, createdBy, createdAt,
                aiGenerated, aiModel, aiPromptVersion, aiGeneratedAt, createdByName, previewImage);
        }

        /** 列表预览图：内容模型图片字段的原值（前端负责代理地址转换/默认图回退）。 */
        ContentInfo withPreviewImage(String previewImage) {
            return new ContentInfo(id, siteId, categoryId, folderId, contentTypeId, title, status,
                currentVersion, routePath, templatePath, objectType, createdBy, createdAt,
                aiGenerated, aiModel, aiPromptVersion, aiGeneratedAt, createdByName, previewImage);
        }
    }

    public record VersionInfo(UUID id, int version, String fieldValues, int modelVersion,
                              String note, String createdBy, java.time.Instant createdAt,
                              boolean aiGenerated, String aiModel, Integer aiPromptVersion,
                              java.time.Instant aiGeneratedAt,
                              String title, String routePath, String templatePath,
                              String createdByName, Integer revertedFrom, boolean published) {
        /**
         * 兼容旧签名（OBS-11-04 之前构造 {@code VersionInfo} 的调用方/用例）：不解析展示名，
         * 界面按 {@code createdByName || createdBy} 回退。
         */
        public VersionInfo(UUID id, int version, String fieldValues, int modelVersion,
                           String note, String createdBy, java.time.Instant createdAt,
                           boolean aiGenerated, String aiModel, Integer aiPromptVersion,
                           java.time.Instant aiGeneratedAt,
                           String title, String routePath, String templatePath) {
            this(id, version, fieldValues, modelVersion, note, createdBy, createdAt,
                aiGenerated, aiModel, aiPromptVersion, aiGeneratedAt, title, routePath, templatePath, null, null,
                false);
        }

        public static VersionInfo from(ContentVersionEntity v) {
            return from(v, null);
        }

        public static VersionInfo from(ContentVersionEntity v, String createdByName) {
            return new VersionInfo(v.getId(), v.getVersion(), v.getFieldValues(),
                v.getModelVersion(), v.getNote(), v.getCreatedBy(), v.getCreatedAt(),
                v.isAiGenerated(), v.getAiModel(), v.getAiPromptVersion(), v.getAiGeneratedAt(),
                v.getTitle(), v.getRoutePath(), v.getTemplatePath(), createdByName, v.getRevertedFrom(),
                v.isPublished());
        }
    }

    public ContentInfo create(UUID siteId, UUID categoryId, UUID folderId, UUID contentTypeId,
                              String title, String routePath, String templatePath,
                              String fieldValues, String createdBy) {
        ContentTypeEntity ct = ctRepo.findById(contentTypeId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在"));
        if (!siteId.equals(ct.getSiteId())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "内容模型不属于当前站点");
        }
        validateCategory(siteId, categoryId);
        SiteFolderEntity folder = validateFolder(siteId, folderId);
        validateCreateType(ct);
        String resolvedRoute = resolveRoute(ct, routePath, folder, title);
        String normalizedRoute = normalizePageRoute(ct, resolvedRoute, null);
        String resolvedTemplate = resolvePageTemplate(ct, templatePath);

        String composed = classCompose.applyOnSave(siteId, contentTypeId, fieldValues);
        composed = validateMediaRefs(siteId, ct, composed);
        validateFieldTypes(ct, composed);
        enforceUniqueFields(ct, null, composed);

        ContentItemEntity item = new ContentItemEntity();
        item.setSiteId(siteId);
        item.setCategoryId(categoryId);
        item.setFolderId(folderId);
        item.setContentTypeId(contentTypeId);
        item.setTitle(title);
        item.setRoutePath(normalizedRoute);
        item.setTemplatePath(resolvedTemplate);
        item.setCreatedBy(createdBy);
        item = itemRepo.save(item);

        ContentVersionEntity v = ContentVersionEntity.create(item.getId(), 1, composed,
            ct.getVersion(), createdBy, "初始版本")
            .withItemSnapshot(item.getTitle(), normalizedRoute, resolvedTemplate);
        versionRepo.save(v);

        item.setCurrentVersion(1);
        itemRepo.save(item);
        events.publishEvent(new ContentChangedEvent(siteId, item.getId()));
        return infoWithActorName(item);
    }

    public VersionInfo saveVersion(UUID itemId, String fieldValues, String note, String createdBy) {
        return saveVersion(itemId, fieldValues, note, null, null, null, createdBy);
    }

    public VersionInfo saveVersion(UUID itemId, String fieldValues, String note, String title,
                                   String routePath, String templatePath, String createdBy) {
        return doSaveVersion(itemId, fieldValues, note, title, routePath, templatePath,
            null, createdBy, null, null, null);
    }

    /**
     * 页面属性绑定（2026-09-22）：folderId 随版本保存一并落到条目级实体列（null = 不变更）。
     * 栏目绑定不进版本快照（与栏目移动同为条目级操作语义）。
     */
    public VersionInfo saveVersion(UUID itemId, String fieldValues, String note, String title,
                                   String routePath, String templatePath, UUID folderId, String createdBy) {
        return doSaveVersion(itemId, fieldValues, note, title, routePath, templatePath,
            folderId, createdBy, null, null, null);
    }

    /**
     * FR-AI-GOV-003：AI 修改作为独立版本写入版本历史——版本元数据标记 AI 参与（模型/提示词版本），
     * 内容条目同步置 AI 标识并落审计；回滚走既有版本机制，历史版本保留 AI 身份。
     */
    public VersionInfo saveAiVersion(UUID itemId, String fieldValues, String note, String title,
                                     String routePath, String templatePath, String createdBy,
                                     String aiModel, Integer aiPromptVersion) {
        VersionInfo version = doSaveVersion(itemId, fieldValues, note, title, routePath, templatePath,
            null, createdBy, aiModel, aiPromptVersion, null);
        ContentItemEntity item = requireItem(itemId);
        item.setAiGenerated(true);
        item.setAiModel(aiModel);
        item.setAiPromptVersion(aiPromptVersion);
        item.setAiGeneratedAt(Instant.now());
        itemRepo.save(item);
        audit.write(createdBy, "ai.content.versioned", "ContentItem", itemId.toString(),
            item.getSiteId(), "success",
            "AI 修改已生成版本 v" + version.version() + "，模型 " + aiModel + "，提示词版本 " + aiPromptVersion);
        return version;
    }

    private VersionInfo doSaveVersion(UUID itemId, String fieldValues, String note, String title,
                                      String routePath, String templatePath, String createdBy,
                                      String aiModel, Integer aiPromptVersion) {
        return doSaveVersion(itemId, fieldValues, note, title, routePath, templatePath,
            null, createdBy, aiModel, aiPromptVersion, null);
    }

    /**
     * @param folderId 页面属性绑定（2026-09-22）：非空时落条目级实体列（null = 不变更）；
     *                 栏目绑定不进版本快照（与栏目移动同为条目级操作语义）
     * @param revertedFrom OBS-11-10：「恢复到 v{n}」产生的版本记录来源版本号，其余路径传 {@code null}
     */
    private VersionInfo doSaveVersion(UUID itemId, String fieldValues, String note, String title,
                                      String routePath, String templatePath, UUID folderId, String createdBy,
                                      String aiModel, Integer aiPromptVersion, Integer revertedFrom) {
        ContentItemEntity item = requireItem(itemId);
        ContentTypeEntity ct = ctRepo.findById(item.getContentTypeId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在"));

        String normalizedRoute = normalizePageRoute(ct, routePath, itemId);
        String resolvedTemplate = resolvePageTemplate(ct, templatePath);
        String composed = classCompose.applyOnSave(item.getSiteId(), item.getContentTypeId(), fieldValues);
        composed = validateMediaRefs(item.getSiteId(), ct, composed);
        validateFieldTypes(ct, composed);
        enforceUniqueFields(ct, itemId, composed);

        int nextVersion = item.getCurrentVersion() + 1;
        // FR-CV-VER-001/003：版本必须记录该次保存后的完整快照，含条目级系统字段（标题/路径/模板），
        // 否则「查看此版本」看不到标题、「恢复到此版本」也无法还原标题。
        String effectiveTitle = (title != null && !title.isBlank()) ? title.trim() : item.getTitle();
        ContentVersionEntity v = ContentVersionEntity.create(itemId, nextVersion, composed,
            ct.getVersion(), createdBy, note, aiModel, aiPromptVersion)
            .withItemSnapshot(effectiveTitle, normalizedRoute, resolvedTemplate)
            .withRevertedFrom(revertedFrom);
        versionRepo.save(v);

        item.setCurrentVersion(nextVersion);
        item.setTitle(effectiveTitle);
        item.setRoutePath(normalizedRoute);
        item.setTemplatePath(resolvedTemplate);
        if (folderId != null) {
            validateFolder(item.getSiteId(), folderId);
            item.setFolderId(folderId);
        }
        itemRepo.save(item);
        events.publishEvent(new ContentChangedEvent(item.getSiteId(), itemId));
        // OBS-11-04：返回体也带上展示名（AI 版本 actor 为用户 ID），避免前端「刚保存就看到 UUID」
        return VersionInfo.from(v, actorNames.resolve(createdBy));
    }

    public VersionInfo revertToVersion(UUID itemId, int version, String actor) {
        return revertToVersion(itemId, version, null, actor);
    }

    /**
     * FR-CV-VER-004/007/012：恢复到指定版本——**不覆盖历史**，而是以目标版本快照为内容创建新版本
     * （「恢复创建新版本」语义），并把新旧版本号写入审计。
     *
     * <p>兼容性校验（FR-CV-VER-004）：目标快照中出现的字段必须在**当前**模型定义中存在（模型可能已
     * 迁移重命名/删除字段）；不一致时返回 400 并给出差异，避免把不可渲染的旧快照写回。</p>
     *
     * <p>标题/页面路径/页面模板取目标版本快照；早期版本（V100 迁移前）该三列为回填值。</p>
     *
     * @param note 变更备注，为空时用默认文案「恢复到 v{n}」
     */
    public VersionInfo revertToVersion(UUID itemId, int version, String note, String actor) {
        ContentItemEntity item = requireItem(itemId);
        ContentVersionEntity target = versionRepo.findByContentItemIdAndVersion(itemId, version)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "版本不存在: v" + version));
        ContentTypeEntity ct = ctRepo.findById(item.getContentTypeId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在"));
        assertSnapshotCompatible(ct, target.getFieldValues(), version);

        String targetTitle = target.getTitle() == null || target.getTitle().isBlank()
            ? item.getTitle() : target.getTitle();
        // PAGE 型必须给出路径：优先用目标版本快照，缺失时沿用当前路径，避免历史行缺列导致恢复失败。
        String targetRoute = target.getRoutePath() != null && !target.getRoutePath().isBlank()
            ? target.getRoutePath()
            : ("PAGE".equalsIgnoreCase(ct.getObjectType()) ? item.getRoutePath() : null);
        String targetTemplate = target.getTemplatePath() != null && !target.getTemplatePath().isBlank()
            ? target.getTemplatePath() : item.getTemplatePath();

        String revertNote = note == null || note.isBlank() ? "恢复到 v" + version : note.trim();
        // OBS-11-10：恢复版本落来源版本号，界面据此标注「恢复自 v{n}」并把默认对比区间收敛为
        // 「来源版本 → 新版本」——否则对比基线退化为上一版本，看不出本次恢复还原了哪些字段。
        VersionInfo restored = doSaveVersion(itemId, target.getFieldValues(),
            revertNote, targetTitle, targetRoute, targetTemplate, null, actor, null, null, version);
        audit.write(actor, "CONTENT_VERSION_REVERT", "ContentItem", itemId.toString(), item.getSiteId(),
            "SUCCESS", "v" + version + " → v" + restored.version());
        return restored;
    }

    /**
     * FR-CV-VER-004 兼容性校验：目标快照的顶层字段键必须都能在当前模型字段或系统字段中找到。
     *
     * <p>不做强制的必填/类型重校验（历史值可能不满足当前约束），只拦截**结构性不兼容**
     * （字段已被删除或重命名），此时恢复出的内容无法正常渲染。</p>
     */
    private void assertSnapshotCompatible(ContentTypeEntity ct, String fieldValues, int version) {
        JsonNode node = parse(fieldValues);
        if (node == null) {
            if (fieldValues != null && !fieldValues.isBlank()) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "版本数据不可解析: v" + version);
            }
            return;
        }
        if (!node.isObject()) return;
        Set<String> known = new LinkedHashSet<>();
        fieldRepo.findByContentTypeIdOrderBySortOrder(ct.getId())
            .forEach(f -> known.add(f.getCode()));
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(name -> {
            // 2026-09-22 系统字段瘦身：历史快照可能残留已退役系统字段键（值另存于版本行的
            // 条目级标题/路径/模板列），豁免之以保证老版本可查看、可恢复。
            if (com.powercms.cms.model.ContentModelValidator.LEGACY_SYSTEM_CODES.contains(name)) return;
            if (!known.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "版本 v" + version + " 含当前模型未定义的字段，无法恢复：" + String.join("、", unknown));
        }
    }

    /**
     * AI 参与标识（FR-AI-GOV-001）：统一写入 AI 参与标记与所用模型/提示词版本，并落审计。
     * 供 AI 生成/修改内容链路（FR-AI-EDIT 域）在落库前调用，标记不可伪造为纯人工。
     */
    public ContentInfo markAiGenerated(UUID itemId, String model, Integer promptVersion, String actor) {
        ContentItemEntity item = requireItem(itemId);
        item.setAiGenerated(true);
        item.setAiModel(model);
        item.setAiPromptVersion(promptVersion);
        item.setAiGeneratedAt(Instant.now());
        itemRepo.save(item);
        audit.write(actor, "ai.content.generated", "ContentItem", itemId.toString(), item.getSiteId(),
            "success", "AI 参与内容，模型 " + model + "，提示词版本 " + promptVersion);
        events.publishEvent(new ContentChangedEvent(item.getSiteId(), itemId));
        return infoWithActorName(item);
    }

    public record InlineFieldResult(UUID contentId, UUID contentTypeId, String fieldCode,
                                    String value, int previousVersion, int currentVersion, String status) {}

    /** Optimistic, single-field contentlet update used by UVE inline editing. */
    public InlineFieldResult updateInlineField(UUID siteId, UUID itemId, String fieldCode, String value,
                                               int expectedVersion, String format, String createdBy) {
        ContentItemEntity item = itemRepo.findByIdAndSiteIdForUpdate(itemId, siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        if (item.getCurrentVersion() != expectedVersion)
            throw new BusinessException(ErrorCodes.CONTENT_VERSION_CONFLICT,
                "版本冲突，期望 " + expectedVersion + "，实际 " + item.getCurrentVersion());
        ContentTypeEntity type = ctRepo.findById(item.getContentTypeId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在"));
        boolean systemTitle = "title".equals(fieldCode);
        FieldDefinitionEntity field = systemTitle ? null : fieldRepo.findByContentTypeIdOrderBySortOrder(type.getId()).stream()
            .filter(candidate -> fieldCode.equals(candidate.getCode())).findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCodes.VALIDATION_FAILED, "字段不存在"));
        if (field != null && (field.isReadOnly() || !java.util.Set.of("STRING", "LONG_TEXT", "RICH_TEXT").contains(field.getFieldType())))
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "该字段不允许内联编辑");
        String nextValue = value == null ? "" : value;
        if (field != null && field.isRequired() && nextValue.isBlank())
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "必填字段不能为空");
        if ("html".equalsIgnoreCase(format)) {
            // 富媒体白名单净化（图片/视频/表格/字体颜色）：与富文本编辑器能力面一致，
            // 详见 RichTextSanitizer（jsoup 结构白名单 + URL/style 后校验）。
            nextValue = com.powercms.cms.delivery.RichTextSanitizer.sanitize(nextValue);
        }
        var current = versionRepo.findByContentItemIdAndVersion(itemId, item.getCurrentVersion())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "版本不存在"));
        com.fasterxml.jackson.databind.node.ObjectNode values;
        try { values = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(current.getFieldValues()); }
        catch (Exception ex) { throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "字段值不是合法 JSON"); }
        if (systemTitle) item.setTitle(nextValue.trim()); else values.put(fieldCode, nextValue);
        int previous = item.getCurrentVersion();
        String composed = classCompose.applyOnSave(siteId, item.getContentTypeId(), values.toString());
        enforceUniqueFields(type, itemId, composed);
        int next = previous + 1;
        versionRepo.save(ContentVersionEntity.create(itemId, next, composed, type.getVersion(), createdBy, "UVE 内联编辑 " + fieldCode));
        item.setCurrentVersion(next); itemRepo.save(item);
        events.publishEvent(new ContentChangedEvent(siteId, itemId));
        return new InlineFieldResult(itemId, item.getContentTypeId(), fieldCode, nextValue, previous, next, item.getStatus());
    }

    public List<ContentInfo> listByCategory(UUID categoryId) {
        return listByCategory(null, categoryId);
    }

    /** P1-E：列表按对象 view 过滤；viewerId=null 表示系统上下文不过滤。 */
    public List<ContentInfo> listByCategory(UUID viewerId, UUID categoryId) {
        List<ContentItemEntity> items = itemRepo.findByCategoryId(categoryId).stream()
            .filter(item -> viewable(viewerId, item))
            .toList();
        return infos(items);
    }

    public List<ContentInfo> listByFolder(UUID folderId) {
        return listByFolder(null, folderId);
    }

    public List<ContentInfo> listByFolder(UUID viewerId, UUID folderId) {
        List<ContentItemEntity> items = itemRepo.findByFolderId(folderId).stream()
            .filter(item -> viewable(viewerId, item))
            .toList();
        return infos(items);
    }

    public List<ContentInfo> listByType(UUID contentTypeId) {
        return listByType(null, contentTypeId);
    }

    public List<ContentInfo> listByType(UUID viewerId, UUID contentTypeId) {
        List<ContentItemEntity> items = itemRepo.findByContentTypeId(contentTypeId).stream()
            .filter(item -> viewable(viewerId, item))
            .toList();
        return infos(items);
    }

    public List<ContentInfo> search(UUID siteId, UUID contentTypeId, List<UUID> categoryIds,
                                    String status, String keyword) {
        return search(siteId, contentTypeId, categoryIds, null, status, keyword);
    }

    public List<ContentInfo> search(UUID siteId, UUID contentTypeId, List<UUID> categoryIds,
                                    UUID folderId, String status, String keyword) {
        return search(null, siteId, contentTypeId, categoryIds, folderId, status, keyword);
    }

    /** P1-E：管理端列表按对象 view 过滤（spec 4.6 / 8.6）。 */
    public List<ContentInfo> search(UUID viewerId, UUID siteId, UUID contentTypeId,
                                    List<UUID> categoryIds, UUID folderId, String status, String keyword) {
        String query = keyword == null ? null : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        var categoryGroups = groupCategorySelections(siteId, categoryIds);
        Set<UUID> hiddenTypes = hiddenSearchTypeIds(siteId, contentTypeId);
        List<ContentItemEntity> matched = itemRepo.findBySiteId(siteId).stream()
            .filter(item -> viewable(viewerId, item))
            .filter(item -> hiddenTypes == null || !hiddenTypes.contains(item.getContentTypeId()))
            .filter(item -> contentTypeId == null || contentTypeId.equals(item.getContentTypeId()))
            .filter(item -> folderId == null || folderId.equals(item.getFolderId()))
            .filter(item -> matchesCategoryGroups(item, categoryGroups))
            .filter(item -> status == null || status.isBlank() || status.equals(item.getStatus()))
            .filter(item -> query == null || query.isBlank()
                || item.getTitle().toLowerCase(java.util.Locale.ROOT).contains(query))
            .sorted(java.util.Comparator.comparing(ContentItemEntity::getUpdatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())).reversed())
            .toList();
        return infos(matched);
    }

    /**
     * 内容检索默认排除的类型（FR-SEARCH 检索范围约定）：
     * MEDIA 实例仅在媒体库展示；系统预置类型（isSystem，如用户/会员资料）仅在用户与权限展示。
     * 显式按类型检索时保留该类型结果。返回 null 表示无需过滤。
     */
    private Set<UUID> hiddenSearchTypeIds(UUID siteId, UUID explicitTypeId) {
        if (explicitTypeId != null) return null;
        return ctRepo.findBySiteIdOrderByCodeAsc(siteId).stream()
            .filter(ct -> "MEDIA".equals(ct.getObjectType()) || ct.isSystem())
            .map(ContentTypeEntity::getId)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * P1-E：内容对象 view 过滤。系统上下文（viewerId=null）放行；
     * 未打破继承链时由站点能力兜底，打破后按 ACL 判定。
     */
    private boolean viewable(UUID viewerId, ContentItemEntity item) {
        if (viewerId == null) return true;
        return objectPermissions.hasRight(viewerId, ObjectType.CONTENT, item.getId(), ObjectType.VIEW);
    }

    private java.util.Map<UUID, java.util.Set<UUID>> groupCategorySelections(
        UUID siteId, List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return java.util.Map.of();
        var categories = categoryRepo.findBySiteId(siteId);
        var byId = categories.stream().collect(java.util.stream.Collectors.toMap(
            com.powercms.cms.content.entity.CategoryEntity::getId, c -> c));
        java.util.Map<UUID, java.util.Set<UUID>> grouped = new java.util.LinkedHashMap<>();
        for (UUID selectedId : categoryIds) {
            var selected = byId.get(selectedId);
            if (selected == null) continue;
            UUID rootId = selected.getId();
            var current = selected;
            while (current.getParentId() != null) {
                rootId = current.getParentId();
                current = byId.get(current.getParentId());
                if (current == null) break;
            }
            java.util.Set<UUID> accepted = grouped.computeIfAbsent(rootId, ignored -> new java.util.LinkedHashSet<>());
            accepted.add(selectedId);
            categories.stream().filter(c -> isDescendantOf(c, selectedId, byId))
                .map(com.powercms.cms.content.entity.CategoryEntity::getId).forEach(accepted::add);
        }
        return grouped;
    }

    private boolean isDescendantOf(
        com.powercms.cms.content.entity.CategoryEntity category, UUID ancestorId,
        java.util.Map<UUID, com.powercms.cms.content.entity.CategoryEntity> byId) {
        UUID parentId = category.getParentId();
        while (parentId != null) {
            if (ancestorId.equals(parentId)) return true;
            var parent = byId.get(parentId);
            parentId = parent == null ? null : parent.getParentId();
        }
        return false;
    }

    private boolean matchesCategoryGroups(
        ContentItemEntity item, java.util.Map<UUID, java.util.Set<UUID>> groups) {
        if (groups.isEmpty()) return true;
        java.util.Set<UUID> itemCategories = new java.util.LinkedHashSet<>();
        if (item.getCategoryId() != null) itemCategories.add(item.getCategoryId());
        JsonNode values = parse(currentFieldValues(item.getId()));
        if (values != null && values.isObject()) collectCategoryValues(values, itemCategories);
        return groups.values().stream().allMatch(
            accepted -> accepted.stream().anyMatch(itemCategories::contains));
    }

    private void collectCategoryValues(JsonNode node, java.util.Set<UUID> categories) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            try { categories.add(UUID.fromString(node.asText())); } catch (IllegalArgumentException ignored) { }
            return;
        }
        if (node.isArray()) node.forEach(child -> collectCategoryValues(child, categories));
        else if (node.isObject()) node.properties().forEach(entry -> collectCategoryValues(entry.getValue(), categories));
    }

    public ContentInfo get(UUID id) { return infoWithActorName(requireItem(id)); }

    public List<VersionInfo> versions(UUID itemId) {
        List<ContentVersionEntity> rows = versionRepo.findByContentItemIdOrderByVersionDesc(itemId);
        // OBS-11-04：AI 版本的用户 ID 与人工版本的用户名混存，列表按用户名展示（一次批量解析）
        Map<String, String> names = actorNames.resolveAll(
            rows.stream().map(ContentVersionEntity::getCreatedBy).toList());
        return rows.stream().map(v -> VersionInfo.from(v, names.get(v.getCreatedBy()))).toList();
    }

    public VersionInfo getVersion(UUID itemId, int version) {
        return versionRepo.findByContentItemIdAndVersion(itemId, version)
            .map(v -> VersionInfo.from(v, actorNames.resolve(v.getCreatedBy())))
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "版本不存在"));
    }

    public void updateStatus(UUID itemId, String newStatus) {
        updateStatus(itemId, newStatus, PublishSource.USER);
    }

    /** 发布入口来源：USER=人工直接操作；WORKFLOW=审批链末步/定时发布；SYSTEM=系统初始化。 */
    public enum PublishSource {
        USER, WORKFLOW, SYSTEM
    }

    /** 发布动作生成的版本备注（DEF-11-02）。 */
    private static final String PUBLISH_NOTE = "发布";

    /** FR-AI-GOV-004：AI 参与内容发布须满足站点审批策略（agent_direct_publish 默认 false）。 */
    public void updateStatus(UUID itemId, String newStatus, PublishSource source) {
        ContentItemEntity item = requireItem(itemId);
        String previousStatus = item.getStatus();
        // DEF-M12-014：状态变更审计必须带**前后值**——管理端审计行的 detail 由当前请求线程挂载，
        // ManagementAuditInterceptor 在请求收尾时取出落库（action 形如 "POST updateStatus"）。
        com.powercms.cms.audit.AuditDetail.set("状态 " + previousStatus + " → " + newStatus);
        if (ContentItemEntity.STATUS_IN_REVIEW.equals(newStatus)
            && !ContentItemEntity.STATUS_IN_REVIEW.equals(previousStatus)) {
            // 进入审核态：先校验必填（TC-M08-014），再补建审核待办（TC-M12-014——否则内容停在
            // IN_REVIEW 却没有任何人有待办）。
            assertRequiredFieldsPresent(itemId);
            ensureReviewTask(item);
        }
        Integer previousPublishedVersion = item.getPublishedVersion();
        // DEF-11-02：发布事件是否成立——首次发布，或自上次发布后内容又发生了变更（currentVersion 前进）。
        // 必须在生成发布版本之前判定：发布版本会把 currentVersion 推进到 publishedVersion，
        // 之后再比较两个字段永远相等，内容未变的重复发布也会被误判为「有发布」而反复落版本。
        boolean publishEvent = ContentItemEntity.STATUS_PUBLISHED.equals(newStatus)
            && (!ContentItemEntity.STATUS_PUBLISHED.equals(previousStatus)
                || !Objects.equals(previousPublishedVersion, item.getCurrentVersion()));
        if (ContentItemEntity.STATUS_PUBLISHED.equals(newStatus)) {
            enforceAiApprovalPolicy(item, source);
            enforcePublishWindow(item);
            PublishChecklist checklist = publishChecklist(itemId);
            if (!checklist.pass()) {
                String firstError = checklist.items().stream()
                    .filter(i -> "ERROR".equals(i.level()))
                    .map(ChecklistItem::message)
                    .findFirst()
                    .orElse("发布检查未通过");
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, firstError,
                    checklist.items().stream()
                        .filter(i -> "ERROR".equals(i.level()) || "WARN".equals(i.level()))
                        .map(i -> new ApiError.FieldError(i.code(), i.message()))
                        .toList());
            }
        }
        item.setStatus(newStatus);
        if (ContentItemEntity.STATUS_IN_REVIEW.equals(previousStatus)
            && !ContentItemEntity.STATUS_IN_REVIEW.equals(newStatus)) {
            item.setInWorkflow(false);
        }
        if (ContentItemEntity.STATUS_IN_REVIEW.equals(newStatus)) {
            item.setInWorkflow(true);
        }
        if (ContentItemEntity.STATUS_PUBLISHED.equals(newStatus)) {
            item.setScheduledPublishAt(null);
            if (publishEvent) {
                // DEF-11-02：发布作为一次版本事件写入版本历史（note=发布、published=true），
                // published_version 指向该版本，版本历史因此可见发布节点。
                ContentTypeEntity ct = ctRepo.findById(item.getContentTypeId())
                    .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在"));
                item.setPublishedVersion(recordPublishVersion(item, ct));
            } else {
                // 内容未变的重复发布（含归档后原样重发）：保持「published_version 指向当前版本」原口径
                item.setPublishedVersion(item.getCurrentVersion());
            }
        }
        if (ContentItemEntity.STATUS_WITHDRAWN.equals(newStatus)) {
            item.setScheduledWithdrawAt(null);
        }
        itemRepo.save(item);
        if (publishEvent) emitPublished(item, previousStatus);
        events.publishEvent(new ContentChangedEvent(item.getSiteId(), itemId));
    }

    /** 批量操作失败项：前端按行展示原因（如「发布检查未通过」「内容不存在」）。 */
    public record BatchFailure(String id, String message) {}

    /** 批量操作结果：逐条成败，部分失败不回滚已成功条目。 */
    public record BatchResult(List<String> succeeded, List<BatchFailure> failed) {}

    /** 批量状态操作允许的目标状态：内容搜索页批量条（提交审核 / 发布 / 设为草稿 / 归档）。 */
    private static final Set<String> BATCH_STATUSES = Set.of(
        ContentItemEntity.STATUS_IN_REVIEW,
        ContentItemEntity.STATUS_PUBLISHED,
        ContentItemEntity.STATUS_DRAFT,
        ContentItemEntity.STATUS_ARCHIVED);

    /**
     * 批量状态流转：逐条复用 {@link #updateStatus}（发布检查、审核待办、审计口径完全一致），
     * 单条失败只记录原因、不影响其余条目。
     */
    public BatchResult batchStatus(List<UUID> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "未选择任何内容");
        }
        // 批量条数按社区基线（矩阵 B7：≤500 条/批）
        if (ids.size() > ContentDeletionService.MAX_BATCH_SIZE) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "单次最多操作 " + ContentDeletionService.MAX_BATCH_SIZE + " 条内容");
        }
        if (status == null || !BATCH_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不支持的批量目标状态：" + status);
        }
        List<String> succeeded = new ArrayList<>();
        List<BatchFailure> failed = new ArrayList<>();
        for (UUID id : ids) {
            try {
                updateStatus(id, status);
                succeeded.add(id.toString());
            } catch (RuntimeException ex) {
                failed.add(new BatchFailure(id.toString(), ex.getMessage()));
            }
        }
        return new BatchResult(succeeded, failed);
    }

    /**
     * 审核待办端口（依赖倒置，FR-SITE-004 / Modulith 边界）。
     *
     * <p><b>为什么需要这个端口</b>：{@code content} 与 {@code workflow} 之间是**双向**关系——
     * {@code workflow} 依赖 {@code content}（{@code WorkflowService} 直接读写
     * {@code ContentService}/{@code ContentItemRepository}），而状态推进又需要
     * {@code workflow} 生成审核待办。若 {@code content} 直接引用
     * {@code com.powercms.cms.workflow.WorkflowService}，Modulith 会判定模块越界
     * （实测：{@code Module 'content' depends on module 'workflow'}），
     * 而把 {@code workflow} 加进 {@code allowedDependencies} 只是把**循环**合法化，并非解决。
     *
     * <p>因此这里做依赖倒置：**接口由使用方（content）定义**，实现方（workflow）实现它。
     * 方向变成单向的 content ← workflow，循环消失。
     *
     * <p><b>方法签名只用 JDK 类型</b>（UUID / String），不出现 {@code WfDefInfo} 等 workflow
     * 自定义类型——否则「返回类型」仍会构成一条 content → workflow 的字节码引用，
     * 越界依旧。这也是为什么用 {@code ensureSystemWorkflowId}（返回 UUID）
     * 而不是 {@code ensureSystemWorkflow(...).id()}。
     *
     * <p>用 {@code ObjectProvider} 注入而非直接注入：{@code workflow} 在构造期需要
     * {@code ContentService}，直接注入会构成构造期循环依赖导致启动失败；
     * {@code ObjectProvider} 把解析推迟到调用时刻，既避免循环又保留「无实现时降级」的语义
     * （单测直构 {@code ContentService} 时端口为空，直接跳过）。
     */
    public interface ReviewTaskPort {
        /** 该内容项是否已有待办审核任务（幂等：避免重复推进时产生重复任务）。 */
        boolean hasPendingReviewTask(UUID contentItemId);

        /**
         * 返回站点的系统工作流定义 ID，不存在则按需创建。
         *
         * <p>返回 {@code UUID} 而非定义对象：见上文的类型边界说明。
         */
        UUID ensureSystemWorkflowId(UUID siteId);

        /**
         * 提交审核（转换校验、指派、审计、通知由实现方保证一致）。
         *
         * <p>方法名与 {@code WorkflowService.submitForReview} 不同是**必需的**：
         * 若同名同签名，实现类里的适配方法会调用到自身而造成无限递归
         * （{@code submitForReview(...) { submitForReview(...); }}），且这在编译期完全合法。
         */
        void submitReviewTask(UUID wfDefId, UUID contentItemId, String targetStatus,
                              String assignee, String actor);
    }

    /**
     * DEF-M12-014：状态接口把内容推进 {@code IN_REVIEW} 时同步生成审核待办。
     *
     * <p>任务创建走 {@code WorkflowService.submitForReview}（与 {@code POST /contents/{id]/submit}
     * 同一条链路：转换校验、指派、审计、通知一致）。工作流未定义该转换时保持状态变更语义、
     * 仅不生成任务并告警，不把配置缺口升级成请求失败。</p>
     */
    private void ensureReviewTask(ContentItemEntity item) {
        if (workflow == null) return; // 单测直构：无工作流协作方
        ReviewTaskPort port = workflow.getIfAvailable();
        if (port == null) return;
        if (port.hasPendingReviewTask(item.getId())) return;
        String actor = AuditService.currentActor();
        try {
            UUID wfId = port.ensureSystemWorkflowId(item.getSiteId());
            port.submitReviewTask(wfId, item.getId(),
                ContentItemEntity.STATUS_IN_REVIEW, actor, actor);
        } catch (BusinessException ex) {
            if (ErrorCodes.WORKFLOW_ACTION_NOT_AVAILABLE.equals(ex.getCode())) {
                log.warn("状态推进到 IN_REVIEW 但工作流未定义该转换，未生成审核任务 item={} err={}",
                    item.getId(), ex.getMessage());
                return;
            }
            throw ex;
        }
    }

    /**
     * DEF-11-02：把「发布」写成版本历史中的一条版本（{@code note=发布}、{@code published=true}）。
     *
     * <p>与 {@link #doSaveVersion} 的差异：发布**不重新提交字段**——不做类级组合串接、媒体引用与
     * 唯一性重校验，只以条目当前内容快照落版本行。否则会出现「保存时合法、发布时因数据漂移
     * （媒体被删、唯一值被同模型其他条目占用）而发布失败」的回归，而发布前所需的校验已由
     * {@link #publishChecklist(UUID)} 覆盖。</p>
     *
     * <p>快照取自 content_version 最新一行（条目表不存字段值）：即 {@link #publishChecklist} 检查过的
     * 那份内容，保证「版本历史里的发布版本」与「实际发布出去的内容」一致。</p>
     *
     * @return 新版本号——调用方据此设置 {@code publishedVersion} 并保存条目
     */
    private int recordPublishVersion(ContentItemEntity item, ContentTypeEntity ct) {
        int nextVersion = item.getCurrentVersion() + 1;
        String snapshot = currentFieldValues(item.getId());
        // 发布留痕不应因为快照缺失而失败（field_values 非空约束）：退化为空对象仅影响该行的查看
        if (snapshot == null || snapshot.isBlank()) snapshot = "{}";
        ContentVersionEntity v = ContentVersionEntity.create(item.getId(), nextVersion, snapshot,
                ct.getVersion(), AuditService.currentActor(), PUBLISH_NOTE)
            .withItemSnapshot(item.getTitle(), item.getRoutePath(), item.getTemplatePath())
            .withPublished(true);
        versionRepo.save(v);
        item.setCurrentVersion(nextVersion);
        return nextVersion;
    }

    /**
     * GOV-004 强制点：AI 参与内容（ai_generated=true）在策略 agent_direct_publish=false 时不得绕过
     * 审批直接发布——仅允许从已审批态（APPROVED/此前已发布/撤回后重发）发布，或由审批链末步
     * （WORKFLOW）发布。违规直接发布被拒并落审计。
     */
    void enforceAiApprovalPolicy(ContentItemEntity item, PublishSource source) {
        if (!item.isAiGenerated()) return;
        if (aiDirectPublishGate.allowsAgentDirectPublish(item.getSiteId())) return;
        if (source == PublishSource.WORKFLOW) return;
        String previous = item.getStatus();
        if (ContentItemEntity.STATUS_APPROVED.equals(previous)
            || ContentItemEntity.STATUS_PUBLISHED.equals(previous)
            || ContentItemEntity.STATUS_WITHDRAWN.equals(previous)) {
            return;
        }
        audit.write(AuditService.currentActor(), "AI_PUBLISH_REJECTED", "ContentItem",
            item.getId().toString(), item.getSiteId(), "failed",
            "AI 参与的内容未审批不可直接发布（agent_direct_publish=false），当前状态 " + previous);
        throw new BusinessException(ErrorCodes.AI_APPROVAL_REQUIRED,
            "AI 参与的内容必须经审批后方可发布（站点 agent_direct_publish=false），请先提交审批");
    }

    private void emitPublished(ContentItemEntity item, String previousStatus) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("siteId", item.getSiteId().toString());
        payload.put("contentItemId", item.getId().toString());
        payload.put("contentTypeId", item.getContentTypeId().toString());
        payload.put("routePath", item.getRoutePath());
        payload.put("publishedVersion", item.getPublishedVersion());
        payload.put("previousStatus", previousStatus);
        try {
            outbox.write("CONTENT_PUBLISHED", "ContentItem", item.getId().toString(), om.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "发布事件写入失败");
        }
        events.publishEvent(new ContentPublishedEvent(
            eventId, item.getSiteId(), item.getId(), item.getContentTypeId(),
            item.getRoutePath(), item.getPublishedVersion() == null ? item.getCurrentVersion() : item.getPublishedVersion(),
            previousStatus));
    }

    public record ChecklistItem(String level, String code, String message) {}
    public record PublishChecklist(boolean pass, List<ChecklistItem> items) {}

    /** FR-SITEAUTH-010：PAGE 发布前字段与可视化组合一致性检查。 */
    @Transactional(readOnly = true)
    public PublishChecklist publishChecklist(UUID itemId) {
        ContentItemEntity item = requireItem(itemId);
        ContentTypeEntity ct = ctRepo.findById(item.getContentTypeId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在"));
        List<ChecklistItem> items = new ArrayList<>();
        boolean pass = true;

        if (item.getTitle() == null || item.getTitle().isBlank()) {
            items.add(new ChecklistItem("ERROR", "title", "标题不能为空"));
            pass = false;
        } else {
            items.add(new ChecklistItem("PASS", "title", "标题已填写"));
        }

        if (item.getCurrentVersion() <= 0) {
            items.add(new ChecklistItem("ERROR", "version", "没有可发布的内容版本"));
            pass = false;
        } else {
            items.add(new ChecklistItem("PASS", "version", "当前版本: " + item.getCurrentVersion()));
        }

        String fieldValues = currentFieldValues(item.getId());
        boolean hasFields = fieldValues != null && !fieldValues.isBlank()
            && !"{}".equals(fieldValues.trim()) && !"null".equalsIgnoreCase(fieldValues.trim());

        if (!"PAGE".equalsIgnoreCase(ct.getObjectType())) {
            if (!hasFields) items.add(new ChecklistItem("WARN", "fields", "字段值为空"));
            else items.add(new ChecklistItem("PASS", "fields", "字段值已保存"));
            return new PublishChecklist(pass, items);
        }

        if (item.getRoutePath() == null || item.getRoutePath().isBlank()) {
            items.add(new ChecklistItem("ERROR", "routePath", "PAGE 缺少路由路径"));
            pass = false;
        } else {
            items.add(new ChecklistItem("PASS", "routePath", "路由: " + item.getRoutePath()));
        }

        String template = item.getTemplatePath();
        if (template == null || template.isBlank()) {
            items.add(new ChecklistItem("ERROR", "templatePath", "PAGE 缺少显示模板"));
            pass = false;
        } else if (!template.trim().toLowerCase(java.util.Locale.ROOT).endsWith(".ftl")
            && !(template.startsWith("/") && template.substring(1).toLowerCase(java.util.Locale.ROOT).endsWith(".ftl"))) {
            items.add(new ChecklistItem("ERROR", "templatePath", "显示模板必须为 .ftl"));
            pass = false;
        } else {
            items.add(new ChecklistItem("PASS", "templatePath", "模板: " + template));
        }

        // 回归缺陷 ①：模板路径仅校验了形态，未校验「渲染源是否真的可解析」——
        // displayTemplate/templatePath 手填不存在的 .ftl 时发布照常成功，交付却 404。
        // 判定与 PageRenderService.render 同源：.ftl 命中 或 页面绑定可视化模板自足（计划 A）。
        String effectiveTemplate = (template == null || template.isBlank()) ? ct.getDisplayTemplate() : template;
        if (effectiveTemplate != null && !effectiveTemplate.isBlank()
            && !pageRenderService.hasRenderableSource(item.getSiteId(), item.getId(), effectiveTemplate)) {
            items.add(new ChecklistItem("ERROR", "templateSource",
                "页面模板不存在: " + effectiveTemplate + "（且未绑定可渲染的可视化模板），发布后将 404"));
            pass = false;
        } else if (effectiveTemplate != null && !effectiveTemplate.isBlank()) {
            items.add(new ChecklistItem("PASS", "templateSource", "渲染源可解析"));
        }

        if (!hasFields) items.add(new ChecklistItem("WARN", "fields", "内容字段为空，仅有布局时访客可能看不到正文"));
        else items.add(new ChecklistItem("PASS", "fields", "内容字段已保存"));

        var composition = compositionService.get(item.getSiteId(), item.getId());
        boolean hasComposition = composition.id() != null && composition.templateId() != null;
        boolean hasZoneComponents = hasComposition && composition.zones() != null
            && composition.zones().values().stream().anyMatch(list -> list != null && !list.isEmpty());

        if (!hasComposition) {
            items.add(new ChecklistItem("WARN", "composition", "尚未保存可视化布局，发布后投放区将为空"));
        } else if (!hasZoneComponents) {
            items.add(new ChecklistItem("WARN", "composition", "已绑定模板但投放区没有组件"));
        } else {
            items.add(new ChecklistItem("PASS", "composition",
                "布局版本 " + composition.version() + "，已包含组件"));
        }

        if (hasComposition && !hasFields) {
            items.add(new ChecklistItem("WARN", "consistency", "有可视化布局但内容字段为空"));
        }
        if (hasFields && !hasComposition) {
            items.add(new ChecklistItem("WARN", "consistency", "有内容字段但尚未保存可视化布局"));
        }
        if (hasFields && hasComposition) {
            items.add(new ChecklistItem("PASS", "consistency", "字段与布局均已保存"));
        }

        return new PublishChecklist(pass, items);
    }

    public List<ContentInfo> batchCreateEpisodes(
        UUID siteId, UUID seasonId, int startNumber, int count, String titlePrefix, String createdBy) {
        if (count < 1 || count > 200) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "批量创建数量须在 1 到 200 之间");
        }
        ContentItemEntity season = requireItem(seasonId);
        if (!siteId.equals(season.getSiteId())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "所选季不属于当前站点");
        }
        ContentTypeEntity episodeType =
            ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, "video_episode").stream()
                .reduce((a, b) -> b)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "尚未初始化视频集模型"));
        String prefix = titlePrefix == null || titlePrefix.isBlank() ? "第" : titlePrefix.trim();
        // FR-EP-BATCH-002：同一季（season_ref_s）内集号（episode_number_i）唯一。先整批校验再创建，
        // 保证「重复即整批拒绝、不产生半成品数据」；类级 @Transactional 另为意外失败兜底回滚。
        assertEpisodeNumbersAvailable(siteId, episodeType.getId(), season.getId(), season.getTitle(),
            startNumber, count);
        List<ContentInfo> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int number = startNumber + i;
            var values = om.createObjectNode();
            values.put("episode_number_i", number);
            values.put("season_ref_s", season.getId().toString());
            created.add(create(siteId, season.getCategoryId(), null, episodeType.getId(),
                prefix + number + "集", null, null, values.toString(), createdBy));
        }
        return created;
    }

    /**
     * 校验「所属季 + 集号」不重复。
     *
     * <p>集号以 {@code field_values} 的 {@code episode_number_i} 存放、所属季以 {@code season_ref_s}
     * 存季内容 ID，故只能按内容模型逐条比对字段值；同一季内已占用的集号与本次请求区间有交集即整批拒绝。</p>
     */
    private void assertEpisodeNumbersAvailable(UUID siteId, UUID episodeTypeId, UUID seasonId,
                                               String seasonTitle, int startNumber, int count) {
        Set<Integer> requested = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            requested.add(startNumber + i);
        }
        String seasonKey = seasonId.toString();
        Set<Integer> taken = new LinkedHashSet<>();
        for (ContentItemEntity item : itemRepo.findByContentTypeId(episodeTypeId)) {
            if (!siteId.equals(item.getSiteId())) continue;
            JsonNode values = parse(currentFieldValues(item.getId()));
            if (values == null || !values.isObject()) continue;
            JsonNode ref = values.get("season_ref_s");
            if (ref == null || !ref.isTextual() || !seasonKey.equalsIgnoreCase(ref.asText().trim())) continue;
            JsonNode number = values.get("episode_number_i");
            if (number == null || !number.isNumber()) continue;
            taken.add(number.asInt());
        }
        List<Integer> conflicts = requested.stream().filter(taken::contains).sorted().toList();
        if (!conflicts.isEmpty()) {
            String numbers = conflicts.stream()
                .map(n -> "第 " + n + " 集")
                .collect(Collectors.joining("、"));
            throw new BusinessException(ErrorCodes.CONFLICT,
                "季《" + seasonTitle + "》中" + numbers + "已存在，本次未创建任何分集（请调整起始集号）");
        }
    }

    private void enforcePublishWindow(ContentItemEntity item) {
        JsonNode values = parse(currentFieldValues(item.getId()));
        if (values == null || !values.isObject()) return;
        Instant now = Instant.now();
        Instant publishAt = instant(values, "publish_at_dt");
        if (publishAt != null && publishAt.isAfter(now)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "未到定时发布时间，暂不能发布");
        }
        Instant copyrightStart = instant(values, "copyright_start_dt");
        if (copyrightStart != null && copyrightStart.isAfter(now)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "版权尚未生效，暂不能发布");
        }
        Instant copyrightEnd = instant(values, "copyright_end_dt");
        if (copyrightEnd != null && copyrightEnd.isBefore(now)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "版权已到期，不能发布");
        }
    }

    private Instant instant(JsonNode values, String code) {
        JsonNode node = values.get(code);
        if (node == null || node.isNull() || isBlank(node)) return null;
        if (node.isNumber()) return Instant.ofEpochMilli(node.asLong());
        String text = node.asText();
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (Exception ignored2) {
                return null;
            }
        }
    }


    private ContentInfo info(ContentItemEntity item) {
        return info(item, null);
    }

    private ContentInfo info(ContentItemEntity item, String previewImage) {
        String objectType = ctRepo.findById(item.getContentTypeId())
            .map(ContentTypeEntity::getObjectType).orElse("PAGE");
        return new ContentInfo(item.getId(), item.getSiteId(), item.getCategoryId(), item.getFolderId(),
            item.getContentTypeId(), item.getTitle(), item.getStatus(), item.getCurrentVersion(),
            item.getRoutePath(), item.getTemplatePath(), objectType, item.getCreatedBy(), item.getCreatedAt(),
            item.isAiGenerated(), item.getAiModel(), item.getAiPromptVersion(), item.getAiGeneratedAt(),
            null, previewImage);
    }

    /**
     * 列表批量转换：预览图按内容模型的图片字段一次解析（无解析器时跳过），
     * 作者展示名一次批量解析，避免逐行查库。
     */
    private List<ContentInfo> infos(List<ContentItemEntity> items) {
        Map<UUID, String> previews = previewImages == null || items.isEmpty()
            ? Map.of()
            : previewImages.resolve(items);
        return withActorNames(items.stream()
            .map(item -> info(item, previews.get(item.getId())))
            .toList());
    }

    /**
     * OBS-11-04：条目级 {@code created_by} 也并非总是用户名——AI 批量建稿（智能体任务
     * {@code BATCH_DRAFT}）写入的是用户 ID，故详情/列表下发前解析展示名。
     */
    private ContentInfo infoWithActorName(ContentItemEntity item) {
        return withActorName(info(item));
    }

    private ContentInfo withActorName(ContentInfo info) {
        return info.withCreatedByName(actorNames.resolve(info.createdBy()));
    }

    /** 列表场景一次批量解析（避免逐行查库）；无 actor 或无需解析时原样返回。 */
    private List<ContentInfo> withActorNames(List<ContentInfo> infos) {
        if (infos.isEmpty()) return infos;
        Map<String, String> names = actorNames.resolveAll(
            infos.stream().map(ContentInfo::createdBy).toList());
        return infos.stream()
            .map(i -> i.withCreatedByName(names.getOrDefault(i.createdBy(), i.createdBy())))
            .toList();
    }

    private void validateCategory(UUID siteId, UUID categoryId) {
        if (categoryId == null) return;
        var category = categoryRepo.findById(categoryId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "CATEGORY 分类不存在"));
        if (!siteId.equals(category.getSiteId())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "CATEGORY 分类不属于当前站点");
        }
    }

    private SiteFolderEntity validateFolder(UUID siteId, UUID folderId) {
        if (folderId == null) return null;
        var folder = folderRepo.findById(folderId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "栏目不存在"));
        if (!siteId.equals(folder.getSiteId())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "栏目不属于当前站点");
        }
        return folder;
    }

    /** 根据栏目路径自动推导页面路径；用户显式指定时优先使用用户值。 */
    private String resolveRoute(ContentTypeEntity ct, String routePath, SiteFolderEntity folder, String title) {
        if (!"PAGE".equalsIgnoreCase(ct.getObjectType())) return null;
        if (routePath != null && !routePath.isBlank()) return routePath;
        if (folder == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "PAGE 必须配置页面路径或选择栏目");
        }
        String slug = toSlug(title);
        String base = folder.getPath();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + slug;
    }

    private static String toSlug(String title) {
        if (title == null || title.isBlank()) return "untitled";
        String slug = title.trim().toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
            .replaceAll("-+", "-")
            .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "untitled" : slug;
    }
    private void validateCreateType(ContentTypeEntity ct) {
        if (ContentTypeEntity.STATUS_DEPRECATED.equals(ct.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "该内容模型已被新版本替代，请在内容菜单中选择最新版本");
        }
        if ("COMPONENT".equalsIgnoreCase(ct.getObjectType())) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "COMPONENT 只能通过页面编辑器或组件引用字段创建");
        }
    }

    private String normalizePageRoute(ContentTypeEntity ct, String routePath, UUID selfId) {
        if (!"PAGE".equalsIgnoreCase(ct.getObjectType())) return null;
        if (routePath == null || routePath.isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "PAGE 必须配置页面路径");
        }
        String route = routePath.trim();
        if (!route.startsWith("/")) route = "/" + route;
        route = route.replaceAll("/{2,}", "/");
        boolean exists = selfId == null
            ? itemRepo.existsBySiteIdAndRoutePath(ct.getSiteId(), route)
            : itemRepo.existsBySiteIdAndRoutePathAndIdNot(ct.getSiteId(), route, selfId);
        if (exists) throw new BusinessException(ErrorCodes.CONFLICT, "页面路径已存在: " + route);
        return route;
    }

    private String resolvePageTemplate(ContentTypeEntity ct, String templatePath) {
        if (!"PAGE".equalsIgnoreCase(ct.getObjectType())) return null;
        String selected = templatePath == null || templatePath.isBlank()
            ? ct.getDisplayTemplate() : templatePath.trim();
        if (selected == null || selected.isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "PAGE 必须选择页面模板");
        }
        String normalized = selected.startsWith("/") ? selected.substring(1) : selected;
        if (!normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".ftl")) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "PAGE 显示模板必须使用 .ftl 扩展名");
        }
        return selected;
    }
    ContentItemEntity requireItem(UUID id) {
        return itemRepo.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
    }

    /**
     * 唯一字段校验（FR-CM-UNIQUE-001~003）：同一内容类型内唯一字段值不可重复。
     * 忽略当前正在编辑的内容项自身；唯一性范围随内容类型（即随站点）执行。
     */
    /**
     * MEDIA_REF 字段规范化。
     *
     * <p>社区版为本地存储模式，不接外部媒资中台，因此不做跨服务引用态校验：
     * 字段值原样保存（外部 URL 与资产 ID 都允许）。
     */
    private String validateMediaRefs(UUID siteId, ContentTypeEntity ct, String fieldValues) {
        return fieldValues;
    }

    private void enforceUniqueFields(ContentTypeEntity ct, UUID selfItemId, String fieldValues) {
        List<FieldDefinitionEntity> uniqueFields =
            fieldRepo.findByContentTypeIdOrderBySortOrder(ct.getId()).stream()
                .filter(FieldDefinitionEntity::isUniqueField)
                .toList();
        if (uniqueFields.isEmpty() || fieldValues == null || fieldValues.isBlank()) return;

        JsonNode incoming = parse(fieldValues);
        if (incoming == null || !incoming.isObject()) return;

        for (FieldDefinitionEntity field : uniqueFields) {
            JsonNode value = incoming.get(field.getCode());
            if (value == null || value.isNull() || isBlank(value)) continue;
            String normalized = value.isValueNode() ? value.asText() : value.toString();
            for (ContentItemEntity other : itemRepo.findByContentTypeId(ct.getId())) {
                if (other.getId().equals(selfItemId)) continue;
                if (normalized.equals(valueOf(other, field.getCode())))
                  throw new BusinessException(
                      ErrorCodes.FIELD_VALUE_DUPLICATE,
                      "字段「" + field.getName() + "」的值已存在，必须唯一",
                      List.of(new ApiError.FieldError(field.getCode(), normalized)));
            }
        }
    }

    /**
     * DEF-M08-013（TC-M08-013）：字段类型强校验。
     *
     * <p>{@code INTEGER/DECIMAL/BOOLEAN/DATE_TIME} 与入参形态不符时按字段级错误
     * （{@code code=VALIDATION_FAILED} + {@code errors[].field}）拒绝保存，而不是「先落库、
     * 内联编辑时才报错」。只校验模型内已定义的字段键（历史快照里的遗留键不在此列），
     * 其余类型（ENUM/JSON/MEDIA_REF/TAG…）保持既有宽松口径，避免误伤自由结构数据。</p>
     */
    private void validateFieldTypes(ContentTypeEntity ct, String fieldValues) {
        JsonNode incoming = parse(fieldValues);
        if (incoming == null || !incoming.isObject()) return;
        List<FieldDefinitionEntity> fields = fieldRepo.findByContentTypeIdOrderBySortOrder(ct.getId());
        if (fields.isEmpty()) return;
        Map<String, FieldDefinitionEntity> byCode = new LinkedHashMap<>();
        for (FieldDefinitionEntity field : fields) byCode.put(field.getCode(), field);

        List<ApiError.FieldError> errors = new ArrayList<>();
        incoming.fields().forEachRemaining(entry -> {
            FieldDefinitionEntity field = byCode.get(entry.getKey());
            JsonNode value = entry.getValue();
            if (field == null || value == null || value.isNull()) return;
            String reason = typeMismatch(field.getFieldType(), value);
            if (reason != null) {
                errors.add(new ApiError.FieldError(field.getCode(),
                    "字段 " + field.getCode() + "（" + field.getName() + "）" + reason));
            }
        });
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "字段类型校验未通过", errors);
        }
        validateI18nLocaleBudget(incoming, fields);
    }

    /**
     * 矩阵 A6（NFR-I18N-002）：i18n 字段的 locale 取值数量以社区版上限为界——
     * 最多 {@value #MAX_I18N_LOCALES} 个 locale（回退链固定为默认语言）。仅统计形如
     * locale 标签的键（{@code zh}、{@code en-US}、{@code zh-Hans}…），
     * 避免把结构化对象误判为多语言取值。
     */
    private void validateI18nLocaleBudget(JsonNode incoming, List<FieldDefinitionEntity> fields) {
        for (FieldDefinitionEntity field : fields) {
            if (!field.isI18n()) continue;
            JsonNode value = incoming.get(field.getCode());
            if (value == null || !value.isObject()) continue;
            int locales = 0;
            java.util.Iterator<String> keys = value.fieldNames();
            while (keys.hasNext()) {
                if (LOCALE_KEY.matcher(keys.next()).matches()) locales++;
            }
            if (locales > MAX_I18N_LOCALES) {
                throw new BusinessException(ErrorCodes.FORBIDDEN,
                    "社区版每个多语言字段最多支持 " + MAX_I18N_LOCALES + " 种语言");
            }
        }
    }

    /** 社区版多语言 locale 上限（矩阵 A6：≤2 个 locale）。 */
    static final int MAX_I18N_LOCALES = 2;

    /** i18n 值对象中视为 locale 标签的键：2~3 位语言码 + 可选 0~2 段区域/文字子标签。 */
    private static final java.util.regex.Pattern LOCALE_KEY =
        java.util.regex.Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}");

    /** @return 不匹配时返回「期望什么」的说明，匹配或无需校验的类型返回 null。 */
    private static String typeMismatch(String fieldType, JsonNode value) {
        if (fieldType == null) return null;
        String expected;
        switch (fieldType) {
            case "INTEGER":
                if (isIntegral(value)) return null;
                expected = "整数 INTEGER，如 42";
                break;
            case "DECIMAL":
                if (isDecimal(value)) return null;
                expected = "数值 DECIMAL，如 3.5";
                break;
            case "BOOLEAN":
                if (isBoolean(value)) return null;
                expected = "布尔值 BOOLEAN（true/false）";
                break;
            case "DATE_TIME":
                if (isDateTime(value)) return null;
                expected = "日期时间 DATE_TIME，如 2026-09-25T08:00:00Z";
                break;
            default:
                return null;
        }
        return "类型不匹配，期望 " + expected + "，实际为 " + describe(value);
    }

    private static String describe(JsonNode v) {
        if (v.isTextual()) return "字符串 \"" + v.asText() + "\"";
        if (v.isArray()) return "数组";
        if (v.isObject()) return "对象";
        if (v.isBoolean()) return "布尔 " + v.asBoolean();
        if (v.isNumber()) return "数值 " + v.asText();
        return String.valueOf(v);
    }

    private static boolean isIntegral(JsonNode v) {
        if (v.isIntegralNumber()) return true;
        if (v.isNumber()) {
            double d = v.doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d);
        }
        if (!v.isTextual()) return false;
        return v.asText().trim().matches("[+-]?\\d{1,18}");
    }

    private static boolean isDecimal(JsonNode v) {
        if (v.isNumber()) return true;
        if (!v.isTextual()) return false;
        String s = v.asText().trim();
        if (s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isBoolean(JsonNode v) {
        if (v.isBoolean()) return true;
        if (!v.isTextual()) return false;
        String s = v.asText().trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(s) || "false".equals(s) || "1".equals(s) || "0".equals(s);
    }

    /** 形态判定：ISO-8601（带/不带时区）、{@code yyyy-MM-dd HH:mm[:ss]}、纯日期、epoch 毫秒。 */
    private static boolean isDateTime(JsonNode v) {
        if (v.isNumber()) return true; // epoch 毫秒
        if (!v.isTextual()) return false;
        String s = v.asText().trim();
        if (s.isEmpty()) return false;

        boolean hasTime = s.length() > 10 && (s.charAt(10) == 'T' || s.charAt(10) == ' ');
        try {
            java.time.LocalDate.parse(hasTime ? s.substring(0, 10) : s);
        } catch (RuntimeException badDate) {
            return false; // 形如 2026-13-45 的非法日期到此为止
        }
        if (!hasTime) return true;

        String time = s.substring(11).trim();
        int zone = time.lastIndexOf('Z');
        if (zone < 0) zone = Math.max(time.lastIndexOf('+'), time.lastIndexOf('-'));
        if (zone > 0) time = time.substring(0, zone).trim(); // 去掉 Z / ±HH:MM
        if (time.isEmpty()) return false;
        try {
            java.time.LocalTime.parse(time);
            return true;
        } catch (RuntimeException badTime) {
            return false;
        }
    }

    /**
     * DEF-M08-014（TC-M08-014）：必填字段校验——required 字段在当前版本为空时拒绝把内容推进
     * {@code IN_REVIEW}。
     *
     * <p>提交审核（{@code WorkflowService.submitForReview}）与状态接口
     * （{@link #updateStatus}）两条链路共用本方法；保存草稿不拦截，草稿允许不完整。</p>
     */
    public void assertRequiredFieldsPresent(UUID itemId) {
        ContentItemEntity item = requireItem(itemId);
        ContentTypeEntity ct = ctRepo.findById(item.getContentTypeId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在"));
        List<FieldDefinitionEntity> required =
            fieldRepo.findByContentTypeIdOrderBySortOrder(ct.getId()).stream()
                .filter(FieldDefinitionEntity::isRequired)
                .toList();
        if (required.isEmpty()) return;

        JsonNode values = parse(currentFieldValues(itemId));
        List<ApiError.FieldError> errors = new ArrayList<>();
        for (FieldDefinitionEntity field : required) {
            JsonNode v = values == null ? null : values.get(field.getCode());
            if (v == null || v.isNull() || isBlank(v)) {
                errors.add(new ApiError.FieldError(field.getCode(),
                    "必填字段不能为空：" + field.getName()));
            }
        }
        if (errors.isEmpty()) return;
        String codes = errors.stream().map(ApiError.FieldError::field)
            .collect(Collectors.joining("、"));
        throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
            "必填字段不能为空：" + codes, errors);
    }

    private String valueOf(ContentItemEntity item, String code) {
        JsonNode values = parse(currentFieldValues(item.getId()));
        if (values == null || !values.isObject()) return null;
        JsonNode v = values.get(code);
        if (v == null || v.isNull() || isBlank(v)) return null;
        return v.isValueNode() ? v.asText() : v.toString();
    }

    private String currentFieldValues(UUID itemId) {
        List<ContentVersionEntity> versions =
            versionRepo.findByContentItemIdOrderByVersionDesc(itemId);
        return versions.isEmpty() ? null : versions.get(0).getFieldValues();
    }

    private JsonNode parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return om.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(JsonNode v) {
        return v.isTextual() && v.asText().isBlank();
    }
}
