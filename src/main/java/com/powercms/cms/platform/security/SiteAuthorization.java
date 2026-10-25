package com.powercms.cms.platform.security;

import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.SiteCapabilities;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.publish.repo.*;
import com.powercms.cms.sitepackage.entity.SiteTemplateAclEntity;
import com.powercms.cms.sitepackage.repo.SiteTemplateAclRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import com.powercms.cms.sitepackage.repo.TemplateProjectRepository;
import com.powercms.cms.workflow.repo.WorkflowDefinitionRepository;
import com.powercms.cms.workflow.repo.WorkflowTaskRepository;
import com.powercms.cms.workflow.repo.NotificationRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.*;

@Component("siteAuthorization")
public class SiteAuthorization {
    public static final String READ = SiteCapabilities.READ;
    public static final String WRITE = SiteCapabilities.WRITE;
    public static final String REVIEW = SiteCapabilities.REVIEW;
    public static final String DEPLOY = SiteCapabilities.DEPLOY;
    public static final String SITE_ADMIN = "site-admin";
    public static final String ADMIN = SiteCapabilities.ADMIN;
    public static final String MODEL = SiteCapabilities.MODEL;
    public static final String TEMPLATE = SiteCapabilities.TEMPLATE;

    public static final String TPL_VIEW = "view";
    public static final String TPL_USE = "use";
    public static final String TPL_EDIT = "edit";
    public static final String TPL_PUBLISH = "publish";

    private final SiteCapabilityEvaluator capabilities;
    private final ObjectPermissionService objectPermissions;
    private final CategoryRepository categories;
    private final ContentItemRepository contents;
    private final ContentTypeRepository contentTypes;
    private final MediaAssetRepository media;
    private final WorkflowDefinitionRepository workflows;
    private final WorkflowTaskRepository tasks;
    private final NotificationRepository notifications;
    private final TemplateProjectRepository projects;
    private final SiteTemplateRepository visualTemplates;
    private final SiteTemplateAclRepository visualTemplateAcls;
    private final BuildJobRepository builds;
    private final ReleaseRepository releases;
    private final DeployTargetRepository targets;
    private final DeploymentRepository deployments;
    private final BuildArtifactRepository artifacts;

    public SiteAuthorization(SiteCapabilityEvaluator capabilities,
            ObjectPermissionService objectPermissions,
            CategoryRepository categories, ContentItemRepository contents,
            ContentTypeRepository contentTypes, MediaAssetRepository media,
            WorkflowDefinitionRepository workflows, WorkflowTaskRepository tasks, NotificationRepository notifications,
            TemplateProjectRepository projects, SiteTemplateRepository visualTemplates,
            SiteTemplateAclRepository visualTemplateAcls,
            BuildJobRepository builds, ReleaseRepository releases, DeployTargetRepository targets,
            DeploymentRepository deployments, BuildArtifactRepository artifacts) {
        this.capabilities = capabilities;
        this.objectPermissions = objectPermissions;
        this.categories = categories; this.contents = contents; this.contentTypes = contentTypes; this.media = media;
        this.workflows = workflows; this.tasks = tasks; this.notifications = notifications; this.projects = projects;
        this.visualTemplates = visualTemplates; this.visualTemplateAcls = visualTemplateAcls;
        this.builds = builds; this.releases = releases; this.targets = targets;
        this.deployments = deployments; this.artifacts = artifacts;
    }

    public boolean check(Authentication authentication, UUID siteId, String permission) {
        UUID userId = userId(authentication);
        if (userId == null || siteId == null) return false;
        if (!capabilities.has(userId, siteId, permission)) return false;
        if (!tokenPermits(authentication, siteId, permission)) {
            throw new BusinessException(ErrorCodes.TOKEN_SCOPE_DENIED, "Token 权限不足");
        }
        return true;
    }

    /** Editors may edit templates via write+ACL; template-engineers use the template capability. */
    public boolean templateWork(Authentication authentication, UUID siteId) {
        UUID userId = userId(authentication);
        if (userId == null || siteId == null) return false;
        boolean hasTemplate = capabilities.has(userId, siteId, TEMPLATE);
        boolean hasWrite = capabilities.has(userId, siteId, WRITE);
        if (!hasTemplate && !hasWrite) return false;
        boolean allowed = (hasTemplate && tokenPermits(authentication, siteId, TEMPLATE))
            || (hasWrite && tokenPermits(authentication, siteId, WRITE));
        if (!allowed) {
            throw new BusinessException(ErrorCodes.TOKEN_SCOPE_DENIED, "Token 权限不足");
        }
        return true;
    }

    /** FR-TPLPERM-001：视觉模板 VIEW/USE/EDIT/PUBLISH。 */
    public boolean visualTemplate(Authentication authentication, UUID templateId, String action) {
        UUID userId = userId(authentication);
        if (userId == null || templateId == null) return false;
        var template = visualTemplates.findById(templateId).orElse(null);
        if (template == null) return false;
        UUID siteId = template.getSiteId();
        if (capabilities.isSystemAdmin(userId) || capabilities.has(userId, siteId, ADMIN)) {
            if (!tokenPermits(authentication, siteId, ADMIN)
                && !tokenPermits(authentication, siteId, TEMPLATE)
                && !tokenPermits(authentication, siteId, WRITE)) {
                throw new BusinessException(ErrorCodes.TOKEN_SCOPE_DENIED, "Token 权限不足");
            }
            return true;
        }

        Set<String> roleCodes = capabilities.siteRoleCodes(userId, siteId);
        List<SiteTemplateAclEntity> acls = visualTemplateAcls.findByTemplateIdOrderByRoleCodeAsc(templateId);
        String act = normalize(action);
        if (!acls.isEmpty()) {
            return acls.stream()
                .filter(acl -> roleCodes.contains(normalize(acl.getRoleCode())))
                .anyMatch(acl -> aclAllows(acl, act));
        }
        if (capabilities.has(userId, siteId, TEMPLATE)) {
            return Set.of(TPL_VIEW, TPL_USE, TPL_EDIT, TPL_PUBLISH).contains(act);
        }
        return roleCodes.stream().anyMatch(role -> defaultTemplatePermit(role, act));
    }

    private static boolean aclAllows(SiteTemplateAclEntity acl, String action) {
        return switch (action) {
            case TPL_VIEW -> acl.isCanView();
            case TPL_USE -> acl.isCanUse();
            case TPL_EDIT -> acl.isCanEdit();
            case TPL_PUBLISH -> acl.isCanPublish();
            default -> false;
        };
    }

    private static boolean defaultTemplatePermit(String role, String action) {
        if ("editor".equals(role)) return Set.of(TPL_VIEW, TPL_USE, TPL_EDIT).contains(action);
        if ("reviewer".equals(role)) return Set.of(TPL_VIEW, TPL_USE).contains(action);
        if ("template-engineer".equals(role) || "site-admin".equals(role) || "content-publisher".equals(role)) {
            return Set.of(TPL_VIEW, TPL_USE, TPL_EDIT, TPL_PUBLISH).contains(action);
        }
        return false;
    }

    public boolean canSee(Authentication authentication, UUID siteId) { return check(authentication, siteId, READ); }
    public boolean category(Authentication a, UUID id, String p) { return object(a, ObjectType.TAXONOMY, id, p); }
    public boolean content(Authentication a, UUID id, String p) { return object(a, ObjectType.CONTENT, id, p); }
    public boolean contentType(Authentication a, UUID id, String p) { return object(a, ObjectType.CONTENT_TYPE, id, p); }
    public boolean media(Authentication a, UUID id, String p) { return object(a, ObjectType.MEDIA, id, p); }

    /**
     * 对象级权限判定（spec 4.6/4.7，P1）：优先对象 ACL（含打破继承链），
     * 整条链未打破时回退站点能力。站点能力通过后仍需 Token scope 校验（不绕过其它权限）。
     */
    public boolean object(Authentication authentication, String objectType, UUID objectId, String right) {
        UUID userId = userId(authentication);
        if (userId == null || objectId == null) return false;
        UUID siteId = siteOf(objectType, objectId);
        if (siteId == null) {
            return "admin".equals(SiteCapabilities.normalize(right)) && capabilities.isSystemAdmin(userId);
        }
        if (!tokenPermits(authentication, siteId, SiteCapabilities.normalize(right))) {
            throw new BusinessException(ErrorCodes.TOKEN_SCOPE_DENIED, "Token 权限不足");
        }
        return objectPermissions.hasRight(userId, objectType, objectId, objectRight(right));
    }

    private UUID siteOf(String objectType, UUID objectId) {
        return switch (objectType) {
            case ObjectType.CONTENT -> contents.findById(objectId).map(x -> x.getSiteId()).orElse(null);
            case ObjectType.TAXONOMY -> categories.findById(objectId).map(x -> x.getSiteId()).orElse(null);
            case ObjectType.CONTENT_TYPE -> contentTypes.findById(objectId).map(x -> x.getSiteId()).orElse(null);
            case ObjectType.MEDIA -> media.findById(objectId).map(x -> x.getSiteId()).orElse(null);
            case ObjectType.SITE -> objectId;
            default -> objectPermissions.siteIdOf(objectType, objectId);
        };
    }

    /** 现有站点能力 key → 对象权利（spec 4.6.1）。 */
    private static String objectRight(String permission) {
        return switch (SiteCapabilities.normalize(permission)) {
            case WRITE, SiteAuthorization.TPL_EDIT -> ObjectType.EDIT;
            case REVIEW, DEPLOY, TPL_PUBLISH -> ObjectType.PUBLISH;
            case SITE_ADMIN, ADMIN -> ObjectType.MANAGE;
            case TPL_USE -> ObjectType.VIEW;
            default -> ObjectType.VIEW;
        };
    }
    public boolean workflow(Authentication a, UUID id, String p) { return resolve(a, workflows.findById(id).map(x -> x.getSiteId()), p); }
    public boolean task(Authentication a, UUID id, String p) {
        return resolve(a, tasks.findById(id).flatMap(t -> contents.findById(t.getContentItemId())).map(x -> x.getSiteId()), p);
    }
    public boolean notification(Authentication a, UUID id) {
        return a != null && notifications.findById(id).map(n -> n.getRecipient().equals(a.getName())).orElse(false);
    }
    public boolean project(Authentication a, UUID id, String p) {
        Optional<UUID> site = projects.findById(id).map(x -> x.getSiteId());
        if (site.isEmpty()) return false;
        if (WRITE.equals(SiteCapabilities.normalize(p))) return templateWork(a, site.get());
        return check(a, site.get(), p);
    }
    public boolean build(Authentication a, UUID id, String p) {
        Optional<UUID> site = builds.findById(id).flatMap(b -> projects.findById(b.getTemplateProjectId())).map(x -> x.getSiteId());
        if (site.isEmpty()) return false;
        if (WRITE.equals(SiteCapabilities.normalize(p))) return templateWork(a, site.get());
        return check(a, site.get(), p);
    }
    public boolean release(Authentication a, UUID id, String p) { return resolve(a, releases.findById(id).map(x -> x.getSiteId()), p); }
    public boolean target(Authentication a, UUID id, String p) { return resolve(a, targets.findById(id).map(x -> x.getSiteId()), p); }
    public boolean deployment(Authentication a, UUID id, String p) { return resolve(a, deployments.findById(id).map(x -> x.getSiteId()), p); }
    public boolean artifact(Authentication a, UUID id, String p) {
        return resolve(a, artifacts.findById(id).flatMap(x -> releases.findById(x.getReleaseId())).map(x -> x.getSiteId()), p);
    }

    private boolean resolve(Authentication a, Optional<UUID> site, String p) { return site.isPresent() && check(a, site.get(), p); }
    private UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        return authentication.getPrincipal() instanceof AuthenticatedUserPrincipal p ? p.getUserId() : null;
    }

    private boolean tokenPermits(Authentication authentication, UUID siteId, String permission) {
        if (!(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal)
            || !principal.isApiToken()) {
            return true;
        }
        if (principal.getTokenSiteId() != null && !principal.getTokenSiteId().equals(siteId)) {
            return false;
        }
        Set<String> scopes = principal.getTokenScopes();
        if (scopes == null || scopes.isEmpty() || scopes.contains("*")) return true;
        return scopes.contains(SiteCapabilities.normalize(permission));
    }

    private String normalize(String code) { return code == null ? "" : code.trim().toLowerCase(Locale.ROOT); }
}
