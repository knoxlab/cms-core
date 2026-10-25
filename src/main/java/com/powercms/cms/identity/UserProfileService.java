package com.powercms.cms.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.cms.content.ContentService;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.model.SystemContentTypeSeedService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 用户资料内容（spec 5.2）：后台用户 → {@code system.user-profile}，前台会员 → {@code system.frontend-user}。
 * 资料内容实例与 sys_user 1:1，经 {@code profile_content_id} 关联；自定义字段经内容类型编辑器扩展。
 */
@Service
@Transactional
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final SystemContentTypeSeedService seedService;
    private final ContentTypeRepository ctRepo;
    private final ContentService contentService;
    private final ContentVersionRepository versionRepo;
    private final SiteRepository siteRepo;
    private final ObjectMapper om;

    public UserProfileService(SystemContentTypeSeedService seedService, ContentTypeRepository ctRepo,
                              ContentService contentService, ContentVersionRepository versionRepo,
                              SiteRepository siteRepo, ObjectMapper om) {
        this.seedService = seedService;
        this.ctRepo = ctRepo;
        this.contentService = contentService;
        this.versionRepo = versionRepo;
        this.siteRepo = siteRepo;
        this.om = om;
    }

    /** 为用户创建资料内容实例并回填 profile_content_id（幂等）。 */
    public void ensureProfile(UserEntity user) {
        if (user.getId() == null || user.getProfileContentId() != null) return;
        UUID siteId = profileSiteOf(user);
        if (siteId == null) {
            log.warn("profile site unavailable user={} kind={}", user.getUsername(), user.getKind());
            return;
        }
        seedService.seed(siteId);
        String code = UserEntity.KIND_FRONTEND.equals(user.getKind())
            ? SystemContentTypeSeedService.FRONTEND_USER
            : SystemContentTypeSeedService.USER_PROFILE;
        ContentTypeEntity type = ctRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, code).stream()
            .reduce((a, b) -> b) // 最新版本
            .orElse(null);
        if (type == null) {
            log.warn("profile content type missing site={} code={}", siteId, code);
            return;
        }
        ObjectNode values = om.createObjectNode();
        values.put("userId_s", user.getId().toString());
        values.put("displayName_s", displayNameOf(user));
        if (user.getEmail() != null && !user.getEmail().isBlank()) values.put("email_s", user.getEmail());
        ContentService.ContentInfo info = contentService.create(siteId, null, null, type.getId(),
            displayNameOf(user), null, null, values.toString(), user.getUsername());
        user.setProfileContentId(info.id());
        log.info("profile content created user={} kind={} contentId={}", user.getUsername(), user.getKind(), info.id());
    }

    /** 读取资料内容字段（Map 深拷贝；无资料返回空）。 */
    public Map<String, Object> readProfile(UUID profileContentId) {
        if (profileContentId == null) return Map.of();
        return versionRepo.findByContentItemIdOrderByVersionDesc(profileContentId).stream()
            .findFirst()
            .map(v -> parse(v.getFieldValues()))
            .orElse(Map.of());
    }

    /**
     * 更新资料内容字段：写入最新版本，并把 email / displayName 双向同步回 sys_user（spec 5.2）。
     */
    public void updateProfile(UserEntity user, Map<String, Object> values, String operator) {
        if (values == null || values.isEmpty()) return;
        if (user.getProfileContentId() == null) ensureProfile(user);
        if (user.getProfileContentId() == null) {
            throw new com.powercms.common.error.BusinessException(
                com.powercms.common.error.ErrorCodes.CONFLICT, "资料内容尚未初始化，无法更新");
        }
        if (values.containsKey("email_s")) {
            Object email = values.get("email_s");
            user.setEmail(email == null ? null : String.valueOf(email));
        }
        // 显示名与 email 同款双向同步：会员/站点成员/后台用户列表读的是 sys_user.display_name，
        // 只写资料内容会让列表一直显示旧值（用户在资料里改了显示名、列表却不变）。
        // 空值/空白回置 null（displayNameOf 与列表按「无显示名」回退到用户名）。
        if (values.containsKey("displayName_s")) {
            Object name = values.get("displayName_s");
            String display = name == null ? null : String.valueOf(name).trim();
            user.setDisplayName(display == null || display.isBlank() ? null : display);
        }
        String fieldValues = write(values);
        contentService.saveVersion(user.getProfileContentId(), fieldValues, "资料更新", operator);
    }

    /** 后台用户资料宿主站点：无 homeSiteId 时取最早的 ACTIVE 站点。 */
    public UUID profileSiteOf(UserEntity user) {
        if (user.getHomeSiteId() != null) return user.getHomeSiteId();
        return siteRepo.findAll().stream()
            .filter(s -> SiteEntity.STATUS_ACTIVE.equals(s.getStatus()))
            .min(Comparator.comparing(SiteEntity::getCreatedAt))
            .map(SiteEntity::getId)
            .orElse(null);
    }

    private static String displayNameOf(UserEntity user) {
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
            ? user.getUsername() : user.getDisplayName();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            JsonNode node = om.readTree(json);
            if (node == null || !node.isObject()) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> result.put(e.getKey(), scalar(e.getValue())));
            return result;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Object scalar(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.numberValue();
        return node.toString();
    }

    private String write(Map<String, Object> values) {
        try {
            return om.writeValueAsString(values);
        } catch (Exception ex) {
            throw new com.powercms.common.error.BusinessException(
                com.powercms.common.error.ErrorCodes.VALIDATION_FAILED, "资料字段序列化失败");
        }
    }
}
