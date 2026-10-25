package com.powercms.cms.identity.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.BuiltInRoleCatalog;
import com.powercms.cms.identity.SiteCapabilities;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.permission.entity.ObjectAclEntity;
import com.powercms.cms.identity.permission.repo.ObjectAclRepository;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对象级 ACL 评估（spec 4.6 / 4.7 / 8.6，P1）。
 *
 * <p>继承规则：对象无个别 ACL（或 inherit=true）时沿父链找最近「打破继承」的祖先，
 * 以其 ACL 为准；整条链均未打破时回退站点能力（P0 语义，等价站点根 ACL 全站授权）。
 * 同一主体多角色与个别用户权利取并集；显式拒绝首期不实现。
 */
@Service
@Transactional
public class ObjectPermissionService {

    private final ObjectAclRepository aclRepo;
    private final PermissionableResolver resolver;
    private final SiteCapabilityEvaluator capabilities;
    private final UserRoleRepository userRoleRepo;
    private final UserRepository userRepo;
    private final SiteRepository siteRepo;
    private final ContentItemRepository contentRepo;
    private final RoleRepository roleRepo;
    private final AuditService audit;
    private final ObjectMapper om;
    private org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    /** 授权门禁：社区版无 License 中间件，能力分级改由 UI 与 API 层裁剪。 */

    public ObjectPermissionService(ObjectAclRepository aclRepo, PermissionableResolver resolver,
                                   SiteCapabilityEvaluator capabilities, UserRoleRepository userRoleRepo,
                                   UserRepository userRepo, SiteRepository siteRepo,
                                   ContentItemRepository contentRepo, RoleRepository roleRepo,
                                   AuditService audit, ObjectMapper om) {
        this.aclRepo = aclRepo;
        this.resolver = resolver;
        this.capabilities = capabilities;
        this.userRoleRepo = userRoleRepo;
        this.userRepo = userRepo;
        this.siteRepo = siteRepo;
        this.contentRepo = contentRepo;
        this.roleRepo = roleRepo;
        this.audit = audit;
        this.om = om;
    }

    /** 对象引用（复用父级解析器的引用类型）。 */
    public static PermissionableResolver.Ref ref(String objectType, UUID objectId) {
        return new PermissionableResolver.Ref(objectType, objectId);
    }

    // ---------- 判定 ----------

    /** 用户对某对象是否具备某权利（匿名 userId=null：按 anonymous 角色评估）。 */
    public boolean hasRight(UUID userId, String objectType, UUID objectId, String right) {
        String r = normalizeRight(right);
        PermissionableResolver.Ref ref = ref(objectType, objectId);
        if (userId != null && capabilities.isSystemAdmin(userId)) return true;
        for (PermissionableResolver.Ref cursor : collectChain(ref)) {
            if (isBroken(cursor)) {
                if (aclAllows(cursor, userId, r)) return true;
                if (userId != null && ownerGrant(cursor, userId, r)) return true;
                return false;
            }
        }
        return capabilityFallback(userId, ref, r);
    }

    /**
     * 用户在对象上的全部有效权利（一次性取回，供交付侧逐对象回显「当前登录用户」的视角权利）。
     *
     * <p>与 {@link #hasRight} 同口径：system-admin 直通全部权利；打破继承后取最近打破祖先 ACL 的
     * 并集（个别用户行 + 其所属角色行，另加 owner 的 view/edit）；整链未打破时回退站点能力映射。
     * 匿名主体不走本方法（前台访客用 {@link #anonymousCanView}/{@link #hasAnonymousRight}）。
     */
    public Set<String> rightsFor(UUID userId, String objectType, UUID objectId) {
        if (userId == null) return Set.of();
        PermissionableResolver.Ref ref = ref(objectType, objectId);
        if (capabilities.isSystemAdmin(userId)) return new LinkedHashSet<>(ObjectType.RIGHTS);
        for (PermissionableResolver.Ref cursor : collectChain(ref)) {
            if (isBroken(cursor)) {
                Set<String> rights = aclRights(cursor, userId);
                for (String right : new String[] {ObjectType.VIEW, ObjectType.EDIT}) {
                    if (ownerGrant(cursor, userId, right)) rights.add(right);
                }
                return rights;
            }
        }
        UUID siteId = siteIdOf(ref);
        return siteId == null ? Set.of() : capabilityRights(userId, siteId);
    }

    /** 匿名主体（前台访客）评估：AC-IAM-030。 */
    public boolean hasAnonymousRight(String objectType, UUID objectId, String right) {
        String r = normalizeRight(right);
        PermissionableResolver.Ref ref = ref(objectType, objectId);
        for (PermissionableResolver.Ref cursor : collectChain(ref)) {
            if (isBroken(cursor)) {
                return aclAllows(cursor, null, r);
            }
        }
        return true; // 未打破继承：维持公开交付现状
    }

    /**
     * 有效授权里是否明确写了匿名角色的「查看」。
     * 整条链都未打破继承时返回 false，不把公开交付的默认值当成站点已开放匿名浏览。
     * 栏目未打破时沿用最近一个打破继承的祖先（通常是站点）；栏目自己打破后只看栏目上的匿名授权。
     */
    public boolean anonymousCanView(String objectType, UUID objectId) {
        EffectiveView viewed = view(objectType, objectId);
        if (viewed.source() == null || viewed.effectiveAcl() == null || viewed.effectiveAcl().isEmpty()) {
            return false;
        }
        UUID roleId = anonymousRoleId(ref(objectType, objectId));
        if (roleId == null) return false;
        for (ObjectAclEntity row : viewed.effectiveAcl()) {
            if (ObjectAclEntity.PRINCIPAL_ROLE.equals(row.getPrincipalType())
                && roleId.equals(row.getPrincipalId())
                && fromJson(row.getPermissions()).contains(ObjectType.VIEW)) {
                return true;
            }
        }
        return false;
    }

    /** 对象是否已打破继承（有个别 ACL 且 inherit=false）。 */
    public boolean isBroken(String objectType, UUID objectId) {
        return isBroken(ref(objectType, objectId));
    }

    /** 某对象是否位于已打破继承的链上（自身或祖先打破）。 */
    public boolean chainBroken(String objectType, UUID objectId) {
        return collectChain(ref(objectType, objectId)).stream().anyMatch(this::isBroken);
    }

    // ---------- 有效权限视图 ----------

    public record EffectiveView(boolean inherit, PermissionableResolver.Ref source,
                                List<ObjectAclEntity> directAcl, List<ObjectAclEntity> effectiveAcl) {}

    public EffectiveView view(String objectType, UUID objectId) {
        PermissionableResolver.Ref ref = ref(objectType, objectId);
        List<ObjectAclEntity> direct = aclRepo.findByObjectTypeAndObjectId(ref.objectType(), ref.objectId());
        boolean inherit = direct.isEmpty() || direct.stream().allMatch(ObjectAclEntity::isInherit);
        PermissionableResolver.Ref source = null;
        List<ObjectAclEntity> effective = List.of();
        for (PermissionableResolver.Ref cursor : collectChain(ref)) {
            if (isBroken(cursor)) {
                source = cursor;
                effective = aclRepo.findByObjectTypeAndObjectId(cursor.objectType(), cursor.objectId());
                break;
            }
        }
        return new EffectiveView(inherit, source, direct, effective);
    }

    // ---------- 写操作 ----------

    /** 待保存的一条授权（主体 + 权利集）。 */
    public record SavePermissionEntry(String principalType, UUID principalId, List<String> permissions) {}

    /**
     * 保存对象 ACL（spec 9.6 PUT /permissions）。
     * <ul>
     *   <li>对象未打破继承且未要求 breakInheritance → 409 PERMISSION_INHERIT_CONFLICT；</li>
     *   <li>要求打破 → 将当前有效权限快照复制为本对象 ACL 后再按主体合并请求条目（spec 4.7）；
     *       同一主体请求条目覆盖快照权利，空权利条目表示移除该主体；</li>
     *   <li>已打破 → 整表重写。</li>
     * </ul>
     */
    public EffectiveView savePermissions(String objectType, UUID objectId,
                                         List<SavePermissionEntry> entries,
                                         boolean breakInheritance, UUID operatorId) {
        // 权限模型本身不按版本裁剪（F1）：对象级 ACL 与继承/打破继承为全版本一致的核心能力，
        // 差异仅在管理端入口是否开放。
        PermissionableResolver.Ref ref = ref(objectType, objectId);
        UUID siteId = siteIdOf(ref);
        boolean broken = isBroken(ref);
        if (!broken && !breakInheritance) {
            throw new BusinessException(ErrorCodes.PERMISSION_INHERIT_CONFLICT,
                "对象尚未打破继承，不能直接设置个别权限；请先打破继承");
        }
        List<ObjectAclEntity> rows = new ArrayList<>();
        if (!broken && breakInheritance) {
            // 快照当前有效权限作为本对象 ACL 起点，并按主体与请求条目合并去重
            Map<String, SavePermissionEntry> merged = new LinkedHashMap<>();
            for (ObjectAclEntity row : snapshotOf(ref)) {
                merged.put(key(row.getPrincipalType(), row.getPrincipalId()),
                    new SavePermissionEntry(row.getPrincipalType(), row.getPrincipalId(),
                        new ArrayList<>(fromJson(row.getPermissions()))));
            }
            for (SavePermissionEntry entry : entries) {
                if (entry.permissions() == null || entry.permissions().isEmpty()) {
                    merged.remove(key(entry.principalType(), entry.principalId()));
                    continue;
                }
                validateEntry(entry);
                merged.put(key(entry.principalType(), entry.principalId()), entry);
            }
            for (SavePermissionEntry entry : merged.values()) {
                rows.add(ObjectAclEntity.of(siteId, ref.objectType(), ref.objectId(),
                    entry.principalType(), entry.principalId(), toJson(entry.permissions()), false, operatorId));
            }
        } else {
            for (SavePermissionEntry entry : entries) {
                if (entry.permissions() == null || entry.permissions().isEmpty()) continue;
                validateEntry(entry);
                rows.add(ObjectAclEntity.of(siteId, ref.objectType(), ref.objectId(),
                    entry.principalType(), entry.principalId(), toJson(entry.permissions()), false, operatorId));
            }
        }
        aclRepo.deleteByObjectTypeAndObjectId(ref.objectType(), ref.objectId());
        // Hibernate 刷新时先插入后删除。不先把删除落库，替换同一主体会撞上 uq_object_acl。
        aclRepo.flush();
        aclRepo.saveAll(rows);
        audit.write(actor(operatorId), "PERMISSION_ACL", ref.objectType(), ref.objectId().toString(),
            siteId, "SUCCESS", "breakInheritance=" + breakInheritance + ", entries=" + rows.size());
        publishChanged(siteId, ref.objectType(), ref.objectId());
        return view(ref.objectType(), ref.objectId());
    }

    /** 恢复继承（spec 4.7 / 9.6 POST /permissions/inherit）：删除个别 ACL。 */
    public void restoreInheritance(String objectType, UUID objectId, UUID operatorId) {
        PermissionableResolver.Ref ref = ref(objectType, objectId);
        if (!isBroken(ref)) {
            throw new BusinessException(ErrorCodes.PERMISSION_INHERIT_CONFLICT, "对象未打破继承，无需恢复");
        }
        UUID siteId = siteIdOf(ref);
        aclRepo.deleteByObjectTypeAndObjectId(ref.objectType(), ref.objectId());
        audit.write(actor(operatorId), "PERMISSION_RESTORE", ref.objectType(), ref.objectId().toString(),
            siteId, "SUCCESS", null);
        publishChanged(siteId, ref.objectType(), ref.objectId());
    }

    // ---------- 内部 ----------

    private boolean isBroken(PermissionableResolver.Ref ref) {
        List<Boolean> flags = aclRepo.inheritFlags(ref.objectType(), ref.objectId());
        return !flags.isEmpty() && Boolean.FALSE.equals(flags.get(0));
    }

    /** 对象 → 最近未打破祖先 ACL 快照；无则空（回退站点能力）。 */
    private List<ObjectAclEntity> snapshotOf(PermissionableResolver.Ref ref) {
        for (PermissionableResolver.Ref cursor : collectChain(ref)) {
            if (isBroken(cursor)) {
                return aclRepo.findByObjectTypeAndObjectId(cursor.objectType(), cursor.objectId());
            }
        }
        return List.of();
    }

    private boolean aclAllows(PermissionableResolver.Ref ref, UUID userId, String right) {
        return aclRights(ref, userId).contains(right);
    }

    /** 主体在该对象 ACL 行上的权利并集（匿名只认 anonymous 角色行）。 */
    private Set<String> aclRights(PermissionableResolver.Ref ref, UUID userId) {
        List<ObjectAclEntity> rows = aclRepo.findByObjectTypeAndObjectId(ref.objectType(), ref.objectId());
        Set<String> out = new LinkedHashSet<>();
        if (rows.isEmpty()) return out;
        Set<UUID> roleIds = new LinkedHashSet<>();
        if (userId != null) {
            userRoleRepo.findByUserId(userId).forEach(b -> roleIds.add(b.getRoleId()));
        }
        UUID anonymousRoleId = null;
        for (ObjectAclEntity row : rows) {
            if (userId == null) {
                // 匿名：仅 anonymous 角色行
                if (!ObjectAclEntity.PRINCIPAL_ROLE.equals(row.getPrincipalType())) continue;
                if (anonymousRoleId == null) anonymousRoleId = anonymousRoleId(ref);
                if (anonymousRoleId != null && anonymousRoleId.equals(row.getPrincipalId())) {
                    out.addAll(fromJson(row.getPermissions()));
                }
                continue;
            }
            boolean match = (ObjectAclEntity.PRINCIPAL_USER.equals(row.getPrincipalType())
                    && row.getPrincipalId().equals(userId))
                || (ObjectAclEntity.PRINCIPAL_ROLE.equals(row.getPrincipalType())
                    && roleIds.contains(row.getPrincipalId()));
            if (match) out.addAll(fromJson(row.getPermissions()));
        }
        return out;
    }

    /** 4.6.4：内容创建者为 owner，默认 view+edit（首期 owner 不含 publish）。 */
    private boolean ownerGrant(PermissionableResolver.Ref ref, UUID userId, String right) {
        if (!ObjectType.CONTENT.equals(ref.objectType()) || !Set.of(ObjectType.VIEW, ObjectType.EDIT).contains(right)) {
            return false;
        }
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null || user.getUsername() == null) return false;
        String createdBy = contentRepo.findById(ref.objectId())
            .map(com.powercms.cms.content.entity.ContentItemEntity::getCreatedBy)
            .orElse(null);
        return createdBy != null && createdBy.equalsIgnoreCase(user.getUsername());
    }

    private boolean capabilityFallback(UUID userId, PermissionableResolver.Ref ref, String right) {
        if (userId == null) return false;
        UUID siteId = siteIdOf(ref);
        if (siteId == null) {
            return ObjectType.MANAGE.equals(right) && capabilities.isSystemAdmin(userId);
        }
        return capabilityRights(userId, siteId).contains(right);
    }

    /** 站点能力 → 对象权利映射（P0 语义，等价站点根 ACL 全站授权）。 */
    private Set<String> capabilityRights(UUID userId, UUID siteId) {
        Set<String> caps = capabilities.capabilities(userId, siteId);
        Set<String> rights = new LinkedHashSet<>();
        if (caps.contains(SiteCapabilities.READ)) rights.add(ObjectType.VIEW);
        if (caps.contains(SiteCapabilities.WRITE)) {
            rights.add(ObjectType.EDIT);
            rights.add(ObjectType.ADD_CHILDREN);
        }
        if (caps.contains(SiteCapabilities.REVIEW) || caps.contains(SiteCapabilities.DEPLOY)) {
            rights.add(ObjectType.PUBLISH);
        }
        if (caps.contains(SiteCapabilities.ADMIN)) rights.add(ObjectType.MANAGE);
        return rights;
    }

    /** 对象链（自身开始向上，至 ORG 结束）。 */
    private List<PermissionableResolver.Ref> collectChain(PermissionableResolver.Ref start) {
        List<PermissionableResolver.Ref> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        PermissionableResolver.Ref cursor = start;
        int guard = 0;
        while (cursor != null && guard++ < 12) {
            String key = cursor.objectType() + ":" + cursor.objectId();
            if (!seen.add(key)) break;
            chain.add(cursor);
            cursor = resolver.parent(cursor.objectType(), cursor.objectId());
        }
        return chain;
    }

    /** 对象所属站点；ORG 返回 null。 */
    public UUID siteIdOf(String objectType, UUID objectId) {
        return siteIdOf(ref(objectType, objectId));
    }

    private UUID siteIdOf(PermissionableResolver.Ref ref) {
        for (PermissionableResolver.Ref cursor : collectChain(ref)) {
            if (ObjectType.SITE.equals(cursor.objectType())) return cursor.objectId();
            if (ObjectType.ORG.equals(cursor.objectType())) return null;
        }
        return null;
    }

    private void publishChanged(UUID siteId, String objectType, UUID objectId) {
        if (events == null || siteId == null) return;
        events.publishEvent(new AccessControlChangedEvent(siteId, objectType, objectId));
    }

    private UUID anonymousRoleId(PermissionableResolver.Ref ref) {
        UUID orgId = null;
        for (PermissionableResolver.Ref cursor : collectChain(ref)) {
            if (ObjectType.ORG.equals(cursor.objectType())) {
                orgId = cursor.objectId();
                break;
            }
        }
        if (orgId == null) {
            // 兜底：首期单组织，从任一站点解析组织
            orgId = siteRepo.findAll().stream().findFirst()
                .map(com.powercms.cms.site.entity.SiteEntity::getOrganizationId).orElse(null);
        }
        if (orgId == null) return null;
        return roleRepo.findByOrganizationIdAndCode(orgId, BuiltInRoleCatalog.ANONYMOUS)
            .map(com.powercms.cms.identity.entity.RoleEntity::getId).orElse(null);
    }

    private void validateEntry(SavePermissionEntry entry) {
        if (entry.principalType() == null
            || !Set.of(ObjectAclEntity.PRINCIPAL_ROLE, ObjectAclEntity.PRINCIPAL_USER).contains(entry.principalType())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "principalType 必须是 ROLE 或 USER");
        }
        if (entry.principalId() == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "principalId 必填");
        }
        if (entry.permissions() == null || entry.permissions().isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "permissions 不能为空");
        }
        for (String p : entry.permissions()) {
            normalizeRight(p);
        }
    }

    /** 主体合并键：principalType:principalId（同对象唯一）。 */
    private static String key(String principalType, UUID principalId) {
        return principalType + ":" + principalId;
    }

    private static String normalizeRight(String right) {
        String r = right == null ? "" : right.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ObjectType.RIGHTS.contains(r)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不支持的对象权利: " + right);
        }
        return r;
    }

    private String toJson(List<String> rights) {
        List<String> normalized = new ArrayList<>();
        for (String r : rights) {
            String value = normalizeRight(r);
            if (!normalized.contains(value)) normalized.add(value);
        }
        try {
            return om.writeValueAsString(normalized);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "权限序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> fromJson(String json) {
        try {
            List<String> values = om.readValue(json, List.class);
            return new LinkedHashSet<>(values);
        } catch (Exception ex) {
            return new LinkedHashSet<>();
        }
    }

    private String actor(UUID operatorId) {
        if (operatorId == null) return "system";
        return userRepo.findById(operatorId).map(UserEntity::getUsername).orElse(operatorId.toString());
    }

    /** 供外部排序/审计引用。 */
    public static Comparator<ObjectAclEntity> byPrincipal() {
        return Comparator.comparing(ObjectAclEntity::getPrincipalType)
            .thenComparing(ObjectAclEntity::getPrincipalId);
    }
}
