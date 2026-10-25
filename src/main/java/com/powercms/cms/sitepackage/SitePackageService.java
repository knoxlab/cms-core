package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.model.ContentModelValidator;
import com.powercms.cms.model.ContentTypeService;
import com.powercms.cms.site.SiteService;
import com.powercms.cms.site.dto.CreateSiteRequest;
import com.powercms.cms.site.dto.SiteResponse;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitepackage.entity.SitePackageEntity;
import com.powercms.cms.sitepackage.repo.SitePackageRepository;
import com.powercms.common.error.ApiError;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.powercms.cms.sitepackage.SitePackagePrecheck.Conflict;
import static com.powercms.cms.sitepackage.SitePackagePrecheck.Resolution;
import static com.powercms.cms.sitepackage.SitePackagePrecheck.SignatureInfo;
import static com.powercms.cms.sitepackage.SitePackageViolations.Violation;

/**
 * 站点包导入/导出服务（FR-PACKAGE-001～008）。
 *
 * <p><b>两阶段执行</b>（FR-PACKAGE-006「原子激活」）：</p>
 * <ol>
 *   <li><b>校验阶段</b>（无副作用）——安全门禁 → 清单必填/版本 → 摘要与签名 → 入口与可执行文件策略
 *       → 模型解析与权威校验 → 冲突分析。任何阻断项都在建站之前汇总返回，因此失败不会留下半成品站点；</li>
 *   <li><b>落库阶段</b>——创建站点、模型、模板工程与来源记录；整个过程在同一事务内，异常即整体回滚。</li>
 * </ol>
 *
 * <p>冲突需显式确认（FR-PACKAGE-005）：预检签发绑定包摘要的处理令牌，导入时回传
 * {@code conflictStrategy} + {@code conflictsToken}，否则 409。</p>
 */
@Service
@Transactional
public class SitePackageService {

    private static final Logger log = LoggerFactory.getLogger(SitePackageService.class);

    /** 校验阶段的一次完整结论（预检与导入共用）。 */
    private record Phase(
        SitePackageArchive.Scan scan,
        SitePackageManifest.View manifest,
        List<SitePackageModels.Def> models,
        List<Violation> violations,
        List<Conflict> conflicts) {

        boolean blocked() {
            return SitePackageViolations.hasBlocking(violations) || SitePackageConflicts.hasBlocking(conflicts);
        }

        boolean confirmable() {
            return SitePackageConflicts.hasConfirmable(conflicts);
        }
    }

    private final SitePackageRepository pkgRepo;
    private final SiteRepository siteRepo;
    private final SiteService siteService;
    private final ContentTypeService ctService;
    private final TemplateService templateService;
    private final ContentModelValidator modelValidator;
    private final SitePackageProperties props;
    private final WebappsService webapps;
    private final com.powercms.cms.model.MediaTypeSeedService mediaTypeSeed;
    private final com.powercms.cms.audit.AuditService auditService;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    SitePackageService(SitePackageRepository pkgRepo, SiteRepository siteRepo, SiteService siteService,
                       ContentTypeService ctService, TemplateService templateService,
                       ContentModelValidator modelValidator, SitePackageProperties props,
                       WebappsService webapps,
                       com.powercms.cms.model.MediaTypeSeedService mediaTypeSeed,
                       com.powercms.cms.audit.AuditService auditService) {
        this.pkgRepo = pkgRepo;
        this.siteRepo = siteRepo;
        this.siteService = siteService;
        this.ctService = ctService;
        this.templateService = templateService;
        this.modelValidator = modelValidator;
        this.props = props;
        this.webapps = webapps;
        this.mediaTypeSeed = mediaTypeSeed;
        this.auditService = auditService;
    }

    // ========== 审计（FR-AUDIT-001：可按主体、对象、动作、结果与时间定位） ==========

    /** 领域级审计：object 为 packageId，siteId 为被导入站点，detail 携带版本/摘要/策略与失败原因。 */
    private void audit(String action, String packageId, UUID siteId, String result, String detail) {
        try {
            auditService.write(com.powercms.cms.audit.AuditService.currentActor(), action,
                "SITE_PACKAGE", packageId, siteId, result, detail);
        } catch (RuntimeException ex) {
            // 审计失败不得影响主业务（FR-AUDIT-001）
            log.error("站点包审计写入失败 action={} packageId={}", action, packageId, ex);
        }
    }

    private String auditDetail(String packageId, String version, String siteKey, String digest,
                               String strategy, String code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("packageId", packageId);
        detail.put("version", version);
        detail.put("siteKey", siteKey);
        detail.put("digest", digest);
        detail.put("strategy", strategy);
        if (code != null) detail.put("errorCode", code);
        if (message != null) detail.put("message", message);
        return toJson(detail);
    }

    private static String digestOf(byte[] zip) {
        try { return SitePackageBuilder.sha256(zip); } catch (RuntimeException ex) { return null; }
    }

    /** 失败审计：尽力从包中解析 packageId，确保「导入失败」也能按对象定位（FR-AUDIT-001）。 */
    private void auditImportFailure(byte[] zipData, String strategy, BusinessException ex) {
        String packageId = null;
        String version = null;
        String siteKey = null;
        try {
            List<Violation> ignored = new ArrayList<>();
            SitePackageArchive.Scan scan = SitePackageArchive.scan(zipData, props);
            var view = SitePackageManifest.parse(scan, props, ignored).orElse(null);
            if (view != null) {
                packageId = view.packageId();
                version = view.version();
                siteKey = view.siteKey();
            }
        } catch (RuntimeException ignore) {
            // 归档级失败（非 ZIP/损坏/安全门禁）：无包标识可解析
        }
        audit("SITE_PACKAGE_IMPORT", packageId, null, "FAILURE",
            auditDetail(packageId, version, siteKey, digestOf(zipData), strategy,
                ex.getCode(), ex.getMessage()));
    }

    // ========== 预检（FR-PACKAGE-005） ==========

    /** 生成冲突报告；不产生任何副作用（除审计外）。 */
    public SitePackagePrecheck.Report precheck(UUID organizationId, byte[] zip) {
        SitePackagePrecheck.Report report;
        try {
            report = toReport(validate(zip));
        } catch (BusinessException ex) {
            // 归档级硬失败（非 ZIP/损坏/安全门禁）也以报告形式返回，前端只需一条渲染路径
            List<Violation> violations = new ArrayList<>();
            List<ApiError.FieldError> details = ex.getDetails();
            if (details.isEmpty()) {
                violations.add(Violation.blocking(ex.getCode(), "archive", ex.getMessage()));
            } else {
                details.forEach(d -> violations.add(Violation.blocking(ex.getCode(), d.field(), d.reason())));
            }
            report = new SitePackagePrecheck.Report(SitePackageBuilder.sha256(zip), null, null, null, null, null,
                0, zip.length, 0, props.getCmsVersion(), null, false, signatureInfo(null),
                violations, List.of(), true, false, List.of());
        }
        audit("SITE_PACKAGE_PRECHECK", report.packageId(), null,
            report.blocked() ? "FAILURE" : "SUCCESS",
            auditDetail(report.packageId(), report.version(), report.siteKey(), report.packageDigest(),
                null, report.blocked() ? "PRE_CHECK_BLOCKED" : null,
                report.blocked() ? SitePackageViolations.summary(List.copyOf(report.violations())) : null));
        return report;
    }

    private SitePackagePrecheck.Report toReport(Phase phase) {
        SitePackageManifest.View view = phase.manifest();
        List<Resolution> resolutions = List.of();
        if (phase.confirmable() && !phase.blocked()) {
            resolutions = SitePackageConflicts.resolutions(phase.conflicts()).stream()
                .map(strategy -> new Resolution(strategy,
                    SitePackageConsent.issue(phase.scan().digest(), strategy, props)))
                .toList();
        }
        return new SitePackagePrecheck.Report(
            phase.scan().digest(),
            view != null ? view.packageId() : null,
            view != null ? view.name() : null,
            view != null ? view.version() : null,
            view != null ? view.schemaVersion() : null,
            view != null ? view.siteKey() : null,
            phase.scan().entries().size(),
            phase.scan().zipSizeBytes(),
            phase.scan().totalUncompressedBytes(),
            props.getCmsVersion(),
            view != null ? view.cmsVersionRange() : null,
            view != null && versionCompatible(phase),
            signatureInfo(view),
            List.copyOf(phase.violations()),
            List.copyOf(phase.conflicts()),
            phase.blocked(),
            phase.confirmable(),
            resolutions);
    }

    private boolean versionCompatible(Phase phase) {
        if (phase.manifest() == null || phase.manifest().cmsVersionRange() == null) return false;
        return phase.violations().stream()
            .noneMatch(v -> ErrorCodes.SITE_PACKAGE_VERSION_INCOMPATIBLE.equals(v.code()));
    }

    private SignatureInfo signatureInfo(SitePackageManifest.View view) {
        if (view == null) {
            return new SignatureInfo(props.getSignaturePolicy().name(), false, null, null, false, "未解析清单");
        }
        String detail = view.signed() ? (view.signatureVerified() ? "签名校验通过" : "签名校验失败")
            : "未签名（策略 " + props.getSignaturePolicy() + "）";
        return new SignatureInfo(props.getSignaturePolicy().name(), view.signed(),
            view.signature() != null ? SitePackageManifest.text(view.signature().get("algorithm")) : null,
            view.signature() != null ? SitePackageManifest.text(view.signature().get("keyId")) : null,
            view.signatureVerified(), detail);
    }

    // ========== 导入（FR-PACKAGE-001～007） ==========

    /** 默认策略导入（无冲突时可用，等价于 {@code conflictStrategy=FAIL}）。 */
    public ImportResult importPackage(UUID organizationId, byte[] zipData) {
        return importPackage(organizationId, zipData, null, null);
    }

    /**
     * 导入站点包。
     *
     * @param conflictStrategy 冲突处理策略（FAIL/RENAME/OVERWRITE/SKIP）；存在可确认冲突时必填
     * @param conflictsToken   预检为所选策略签发的确认令牌；存在可确认冲突时必填
     */
    public ImportResult importPackage(UUID organizationId, byte[] zipData, String conflictStrategy,
                                      String conflictsToken) {
        try {
            return doImport(organizationId, zipData, conflictStrategy, conflictsToken);
        } catch (BusinessException ex) {
            auditImportFailure(zipData, normalizeStrategy(conflictStrategy), ex);
            throw ex;
        }
    }

    private ImportResult doImport(UUID organizationId, byte[] zipData, String conflictStrategy,
                                  String conflictsToken) {
        Phase phase = validate(zipData);
        if (phase.manifest() == null) {
            // 清单缺失/解析失败：违规已在校验阶段汇总
            throw primaryViolation("站点包校验失败", phase.violations());
        }
        if (SitePackageViolations.hasBlocking(phase.violations())) {
            throw primaryViolation("站点包校验失败（" + SitePackageViolations.blockingOnly(phase.violations()).size()
                + " 项阻断）", phase.violations());
        }
        if (SitePackageConflicts.hasBlocking(phase.conflicts())) {
            throw new BusinessException(ErrorCodes.SITE_PACKAGE_CONFLICT_BLOCKED,
                "站点包与当前实例存在不可解决的冲突",
                SitePackageViolations.details(phase.conflicts().stream()
                    .filter(c -> SitePackageConflicts.SEVERITY_BLOCKING.equals(c.severity()))
                    .map(c -> Violation.blocking(ErrorCodes.SITE_PACKAGE_CONFLICT_BLOCKED, c.objectKey(), c.detail()))
                    .toList()));
        }

        String strategy = normalizeStrategy(conflictStrategy);
        if (phase.confirmable()) {
            if (strategy == null) {
                throw new BusinessException(ErrorCodes.SITE_PACKAGE_CONFIRMATION_REQUIRED,
                    "该站点包存在冲突，必须先确认处理策略（可选："
                        + String.join("/", SitePackageConflicts.resolutions(phase.conflicts())) + "）",
                    SitePackageConflicts.confirmableDetails(phase.conflicts()));
            }
            if (!SitePackageConflicts.strategyApplicable(phase.conflicts(), strategy)) {
                throw new BusinessException(ErrorCodes.BAD_REQUEST,
                    "所选策略 " + strategy + " 不适用于当前冲突（可选："
                        + String.join("/", SitePackageConflicts.resolutions(phase.conflicts())) + "）",
                    SitePackageConflicts.confirmableDetails(phase.conflicts()));
            }
            SitePackageConsent.verify(conflictsToken, phase.scan().digest(), strategy, props);
        }

        SitePackageManifest.View view = phase.manifest();
        log.info("site package import start packageId={} version={} strategy={} violations={} conflicts={}",
            view.packageId(), view.version(), strategy, phase.violations().size(), phase.conflicts().size());
        phase.violations().stream().filter(v -> v.severity() == SitePackageViolations.Severity.WARN)
            .forEach(v -> log.warn("站点包导入告警 object={} detail={}", v.object(), v.detail()));

        // ---------- 落库阶段（校验已全部通过） ----------
        String siteKey = "RENAME".equals(strategy) ? uniqueSiteKey(view.siteKey()) : view.siteKey();
        SiteResponse site = siteService.create(organizationId,
            new CreateSiteRequest(siteKey, view.name(), null, null, null, null), null);

        // 平台预置媒体类型（media_image 等）先行种子：常规站点创建走 AFTER_COMMIT 事件补种，
        // 而导入在同一事务内紧接着创建包内模型——媒体引用字段（如 digital_asset.storage_uri_s）
        // 的引用校验要求这些类型已存在，否则导入被自身校验阻断。seed 幂等，事件补种不会重复创建。
        mediaTypeSeed.seed(site.id());
        java.util.Set<String> seededCodes = ctService.list(site.id()).stream()
            .map(m -> m.code()).collect(java.util.stream.Collectors.toSet());
        List<String> skippedPresets = new ArrayList<>();

        for (SitePackageModels.Def def : phase.models()) {
            // 平台预置模型（media_* 等）由上方种子先行创建；包内若又定义同名模型，
            // 不做覆盖也不报重复，而是跳过并留痕（预置归平台管，见导出侧同款剔除）。
            if (isPlatformPreset(def.request().code()) && seededCodes.contains(def.request().code())) {
                skippedPresets.add(def.request().code());
                continue;
            }
            var created = ctService.create(site.id(), def.request());
            ctService.publish(site.id(), created.id(), "system", null);
        }

        if (phase.scan().has("template/package.json")) {
            var proj = templateService.createProject(site.id(), view.name(),
                view.templateRuntimeType() != null ? view.templateRuntimeType() : "REACT");
            phase.scan().entries().entrySet().stream()
                .filter(e -> e.getKey().startsWith("template/"))
                .sorted(java.util.Comparator.comparingInt(e -> SitePackageBuilder.importOrder(e.getKey())))
                .forEach(e -> templateService.saveFile(proj.id(),
                    e.getKey().substring("template/".length()),
                    new String(e.getValue(), StandardCharsets.UTF_8)));
            templateService.commit(proj.id(), "system", "import from package " + view.packageId());
        }

        SitePackageEntity pkg = recordPackage(view, phase, site, zipData.length);

        // 站点应用部署：REACT/VUE 前端产物落到 webapps/{siteKey}/（可直接访问）；
        // 失败不回滚导入本身（站点/模型已就绪是主目标），但在结果与审计中显式暴露，不做静默丢弃。
        String webappsPath = null;
        String webappsDetail;
        try {
            var deployed = webapps.deployFromPackage(site.siteKey(), view.templateRuntimeType(),
                view.entrypoints(), phase.scan().entries());
            if (deployed.isPresent()) {
                webappsPath = deployed.get().path();
                webappsDetail = "，站点应用 webapps/" + webappsPath + "（" + deployed.get().fileCount() + " 文件）";
            } else {
                webappsDetail = "，站点应用未部署";
            }
        } catch (RuntimeException ex) {
            log.warn("站点应用部署失败 siteKey={}：{}", site.siteKey(), ex.getMessage());
            webappsDetail = "，站点应用部署失败: " + ex.getMessage();
        }

        audit("SITE_PACKAGE_IMPORT", view.packageId(), site.id(), "SUCCESS",
            auditDetail(view.packageId(), view.version(), site.siteKey(), phase.scan().digest(),
                strategy, null, "模型 " + phase.models().size() + " 个，文件 " + phase.scan().entries().size() + " 个"
                    + (skippedPresets.isEmpty() ? "" : "，跳过预置模型 " + String.join("、", skippedPresets))
                    + webappsDetail));
        log.info("site package import done packageId={} site={} siteKey={}", view.packageId(), site.id(), siteKey);
        return new ImportResult(site.id(), pkg.getId(), site.siteKey(), webappsPath);
    }

    private SitePackageEntity recordPackage(SitePackageManifest.View view, Phase phase, SiteResponse site,
                                            int zipSize) {
        var existing = pkgRepo.findByPackageIdAndVersion(view.packageId(), view.version());
        if (existing.isPresent()) {
            log.info("site package record exists, rebinding to new site packageId={} version={} previousSite={}",
                view.packageId(), view.version(), existing.get().getImportedSiteId());
        }
        SitePackageEntity pkg = existing.orElseGet(SitePackageEntity::new);
        pkg.setPackageId(view.packageId());
        pkg.setName(view.name());
        pkg.setVersion(view.version());
        pkg.setSchemaVersion(view.schemaVersion() != null ? view.schemaVersion() : "1.0");
        pkg.setVendor(toJson(view.raw().get("vendor")));
        pkg.setLicenseSpdx(view.licenseSpdx());
        pkg.setCmsVersionRange(view.cmsVersionRange());
        pkg.setTemplateRuntime(toJson(view.raw().get("templateRuntime")));
        pkg.setEntrypoints(toJson(view.entrypoints()));
        pkg.setRequiredCapabilities(toJson(view.requiredCapabilities()));
        pkg.setManifestChecksums(toJson(view.checksums()));
        pkg.setSignature(toJson(view.signature()));
        pkg.setPackageDigest(phase.scan().digest());
        pkg.setFileSize(zipSize);
        pkg.setFileCount(phase.scan().entries().size());
        pkg.setImportedSiteId(site.id());
        pkg.setImportedAt(Instant.now());
        return pkgRepo.save(pkg);
    }

    // ========== 校验阶段（无副作用，预检/导入共用） ==========

    private Phase validate(byte[] zipData) {
        List<Violation> violations = new ArrayList<>();
        SitePackageArchive.Scan scan = SitePackageArchive.scan(zipData, props);
        var view = SitePackageManifest.parse(scan, props, violations).orElse(null);
        List<SitePackageModels.Def> models = view == null
            ? List.of()
            : SitePackageModels.validate(scan, modelValidator, violations);
        List<Conflict> conflicts = view == null ? List.of() : SitePackageConflicts.analyze(
            view.siteKey(), view.packageId(), view.version(),
            models.stream().map(SitePackageModels.Def::code).toList(),
            view.requiredCapabilities(), scan.entries(), props,
            siteRepo.existsBySiteKey(view.siteKey()),
            pkgRepo.findByPackageIdAndVersion(view.packageId(), view.version()).isPresent());
        return new Phase(scan, view, models, violations, conflicts);
    }

    /** 阻断时按语义优先级选择对外错误码，保证探针/前端能稳定判定失败类别。 */
    private static final List<String> ERROR_PRIORITY = List.of(
        ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED,
        ErrorCodes.SITE_PACKAGE_STRUCTURE_INVALID,
        ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
        ErrorCodes.SITE_PACKAGE_VERSION_INCOMPATIBLE,
        ErrorCodes.SITE_PACKAGE_CHECKSUM_MISMATCH,
        ErrorCodes.SITE_PACKAGE_EXECUTABLE_FORBIDDEN,
        ErrorCodes.SITE_PACKAGE_SIGNATURE_REQUIRED,
        ErrorCodes.SITE_PACKAGE_SIGNATURE_INVALID,
        ErrorCodes.SITE_PACKAGE_MODEL_INVALID);

    private BusinessException primaryViolation(String summary, List<Violation> violations) {
        List<Violation> blocking = SitePackageViolations.blockingOnly(violations);
        String code = ERROR_PRIORITY.stream()
            .filter(candidate -> blocking.stream().anyMatch(v -> candidate.equals(v.code())))
            .findFirst()
            .orElse(blocking.isEmpty() ? ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID : blocking.getFirst().code());
        return new BusinessException(code, summary + "：" + SitePackageViolations.summary(blocking),
            SitePackageViolations.details(violations));
    }

    private static String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) return null;
        String normalized = strategy.trim().toUpperCase(java.util.Locale.ROOT);
        return "FAIL".equals(normalized) ? null : normalized;
    }

    /** RENAME：在 siteKey 后追加 -2/-3… 直到不冲突。 */
    private String uniqueSiteKey(String base) {
        String candidate = base;
        int suffix = 1;
        while (siteRepo.existsBySiteKey(candidate)) {
            suffix++;
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    /** 平台预置模型（由 {@link com.powercms.cms.model.MediaTypeSeedService} 补种，导入/导出均不搬运）。 */
    private static boolean isPlatformPreset(String code) {
        return com.powercms.cms.model.MediaTypeSeedService.seeds().stream()
            .anyMatch(s -> s.code().equals(code));
    }

    // ========== 导出（FR-PACKAGE-008） ==========

    /** 导出为同规范站点包：清单必填项齐全、文件摘要覆盖全包、有签名密钥时自动签名、不含任何凭据。 */
    public byte[] exportPackage(UUID siteId) {
        SiteResponse site = siteService.getById(siteId);
        var models = ctService.list(siteId);
        var projects = templateService.listProjects(siteId);
        String projectType = projects.isEmpty() ? "REACT" : projects.getFirst().projectType();

        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> omittedSystemModels = new ArrayList<>();
        int exportedModels = 0;
        for (var m : models) {
            // FR-PACKAGE-007：系统内容模型（system.*）与平台预置媒体模型（media_* 等）由目标实例在
            // 站点创建时自动补种，且导入侧不得覆盖 —— 导出必须剔除，否则导出包语义上不可往返
            // （FR-PACKAGE-008）：重导入会与目标实例的补种撞码。剔除项在包内文档留痕，不静默丢弃。
            if (SitePackageModels.isReserved(m.code(), props) || isPlatformPreset(m.code())) {
                omittedSystemModels.add(m.code());
                continue;
            }
            Map<String, Object> modelDef = new LinkedHashMap<>();
            modelDef.put("code", m.code());
            modelDef.put("name", m.name());
            modelDef.put("description", m.description());
            // 模型级配置（客户端表单控制器 / 控制器 / 快速创建等）随包往返，避免导出后重导入被重置为默认值
            modelDef.putAll(SitePackageModels.exportAttributes(m));
            var exportFields = SitePackageModels.exportFields(m);
            if (!exportFields.isEmpty()) modelDef.put("fields", exportFields);
            var exportLayout = SitePackageModels.exportLayout(m);
            if (exportLayout != null) modelDef.put("layout", exportLayout);
            files.put("models/" + m.code() + ".yaml", yamlBytes(modelDef));
            exportedModels++;
        }
        if (!projects.isEmpty()) {
            var proj = projects.getFirst();
            for (var f : templateService.listFiles(proj.id())) {
                files.put("template/" + f.path(), f.content().getBytes(StandardCharsets.UTF_8));
            }
            // FR-PACKAGE-008：导入器以 template/package.json 判定「是否携带前端工程」，
            // FTL 等工程并无该文件，缺失会让模板文件在往返时被静默丢弃 → 这里补最小清单。
            if (!files.containsKey("template/package.json")) {
                files.put("template/package.json", templateManifestBytes(site, projectType));
            }
        }
        if (!omittedSystemModels.isEmpty()) {
            // 不静默丢弃：把剔除原因写进包内文档，预检报告会以 SECTION 提示项暴露。
            files.put("docs/omitted-system-models.md", omittedModelsNote(omittedSystemModels));
        }

        Map<String, Object> manifest = exportManifest(site, projectType, exportedModels);
        String keyId = props.defaultSigningKeyId();
        String secret = keyId != null ? props.signingKey(keyId) : null;
        byte[] export = SitePackageBuilder.build(manifest, files, keyId, secret);
        audit("SITE_PACKAGE_EXPORT", String.valueOf(manifest.get("packageId")), siteId, "SUCCESS",
            auditDetail(String.valueOf(manifest.get("packageId")), "1.0.0", site.siteKey(),
                SitePackageBuilder.sha256(export), null, null,
                "文件 " + files.size() + " 个，签名 " + (keyId != null ? keyId : "无")));
        return export;
    }

    private Map<String, Object> exportManifest(SiteResponse site, String projectType, int modelCount) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "1.0");
        manifest.put("packageId", "com.powercms.site." + SitePackageManifest.sanitizeKey(site.siteKey()));
        manifest.put("name", site.name());
        manifest.put("version", "1.0.0");
        manifest.put("siteKey", site.siteKey());
        manifest.put("cmsVersionRange", ">=" + props.getCmsVersion());
        manifest.put("vendor", Map.of("name", "PowerCMS", "source", "site-export"));
        manifest.put("license", Map.of("spdx", "UNLICENSED", "name", "Site export"));
        manifest.put("templateRuntime", Map.of("type", projectType, "nodeVersion", "20"));
        manifest.put("entrypoints", Map.of("build", "npm run build", "output", "dist"));
        List<String> capabilities = new ArrayList<>();
        capabilities.add("content-type");
        capabilities.add("template-" + projectType.toLowerCase(java.util.Locale.ROOT));
        if (modelCount == 0) capabilities.remove("content-type");
        manifest.put("requiredCapabilities", capabilities);
        return manifest;
    }

    /** 导出包内补写的最小前端工程清单（FR-PACKAGE-008 往返保真）。 */
    private byte[] templateManifestBytes(SiteResponse site, String projectType) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("name", SitePackageManifest.sanitizeKey(site.siteKey()) + "-template");
        pkg.put("version", "1.0.0");
        pkg.put("private", true);
        pkg.put("powercms", Map.of(
            "runtime", projectType,
            "generatedBy", "site-export",
            "note", "导出时按最小必要字段补写；包内 template/ 为该站点模板工程文件"));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsBytes(pkg);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "导出失败: " + e.getMessage());
        }
    }

    /** 被剔出包的系统模型说明（避免「导出后少了模型」被当成静默丢失）。 */
    private byte[] omittedModelsNote(List<String> codes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 导出时剔除的系统内容模型\n\n");
        sb.append("以下模型由平台在站点创建时自动补种（FR-PACKAGE-007 站点隔离），\n");
        sb.append("且导入侧禁止覆盖系统模型（FR-PACKAGE-005），因此未纳入本包：\n\n");
        codes.stream().sorted().forEach(code -> sb.append("- `").append(code).append("`\n"));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] yamlBytes(Map<String, Object> value) {        try {
            return new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).writeValueAsBytes(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "导出失败: " + e.getMessage());
        }
    }

    // ========== 查询 ==========

    public record ImportResult(UUID siteId, UUID packageId, String siteKey, String webappsPath) {}

    public record PkgInfo(UUID id, String packageId, String name, String version,
                          String schemaVersion, UUID importedSiteId,
                          Instant importedAt, Instant createdAt,
                          String packageDigest, String cmsVersionRange, String licenseSpdx) {
        public static PkgInfo from(SitePackageEntity p) {
            return new PkgInfo(p.getId(), p.getPackageId(), p.getName(), p.getVersion(),
                p.getSchemaVersion(), p.getImportedSiteId(), p.getImportedAt(), p.getCreatedAt(),
                p.getPackageDigest(), p.getCmsVersionRange(), p.getLicenseSpdx());
        }
    }

    public List<PkgInfo> listPackages() {
        return pkgRepo.findAllByOrderByCreatedAtDesc().stream().map(PkgInfo::from).toList();
    }

    public PkgInfo getPackage(UUID id) { return PkgInfo.from(requirePkg(id)); }

    private SitePackageEntity requirePkg(UUID id) {
        return pkgRepo.findById(id).orElseThrow(() ->
            new BusinessException(ErrorCodes.NOT_FOUND, "站点包不存在"));
    }

    private String toJson(Object obj) {
        try { return obj != null ? jsonMapper.writeValueAsString(obj) : null; } catch (Exception e) { return null; }
    }
}
