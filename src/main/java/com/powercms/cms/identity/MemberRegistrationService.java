package com.powercms.cms.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.identity.dto.CreateUserRequest;
import com.powercms.cms.identity.dto.UserResponse;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.model.SystemContentTypeSeedService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.entity.FormLayoutEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.model.repo.FormLayoutRepository;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 前台自助注册：表单项来自已发布的 {@code system.frontend-user} 及其表单布局里显示的字段。
 * 站点授权策略决定注册后直接通过，还是进入待审核。
 */
@Service
@Transactional
public class MemberRegistrationService {

    private static final Set<String> SIMPLE_TYPES = Set.of(
        "STRING", "LONG_TEXT", "RICH_TEXT", "INTEGER", "DECIMAL", "BOOLEAN",
        "ENUM", "MULTI_ENUM", "DATE", "DATE_TIME");

    private final SiteRepository sites;
    private final ContentTypeRepository types;
    private final FieldDefinitionRepository fields;
    private final FormLayoutRepository layouts;
    private final IdentityService identity;
    private final ObjectMapper mapper;

    public MemberRegistrationService(SiteRepository sites, ContentTypeRepository types,
                                     FieldDefinitionRepository fields, FormLayoutRepository layouts,
                                     IdentityService identity, ObjectMapper mapper) {
        this.sites = sites;
        this.types = types;
        this.fields = fields;
        this.layouts = layouts;
        this.identity = identity;
        this.mapper = mapper;
    }

    public record RegistrationField(String code, String name, String fieldType, boolean required,
                                    String helpText, List<String> options) {}

    public record RegistrationForm(String siteKey, String approval, List<RegistrationField> fields) {}

    public record RegistrationResult(String username, String displayName, String status, boolean autoLogin) {}

    public RegistrationForm form(UUID siteId) {
        SiteEntity site = requireSite(siteId);
        boolean review = identity.memberRegistrationRequiresReview(siteId);
        return new RegistrationForm(site.getSiteKey(), review ? "REVIEW" : "AUTO", visibleFields(siteId));
    }

    public RegistrationResult register(UUID siteId, String username, String password, Map<String, Object> submitted) {
        SiteEntity site = requireSite(siteId);
        String account = username == null ? "" : username.trim();
        if (!account.matches("[A-Za-z0-9._-]{3,100}")) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "用户名仅允许字母数字和 ._-，至少 3 位");
        }
        List<RegistrationField> visible = visibleFields(siteId);
        Map<String, Object> profile = new LinkedHashMap<>();
        for (RegistrationField field : visible) {
            Object raw = submitted == null ? null : submitted.get(field.code());
            String text = raw == null ? "" : String.valueOf(raw).trim();
            boolean empty = text.isEmpty() || "null".equals(text);
            if (field.required() && empty && !"BOOLEAN".equals(field.fieldType())) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "请填写" + field.name());
            }
            if (!empty) profile.put(field.code(), coerce(field, raw, text));
        }
        String displayName = textOf(profile.get("displayName_s"));
        if (displayName.isBlank()) displayName = account;
        String email = textOf(profile.get("email_s"));
        if (!email.isBlank() && !email.contains("@")) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "邮箱格式不正确");
        }
        boolean review = identity.memberRegistrationRequiresReview(siteId);
        UserResponse created = identity.createUser(site.getOrganizationId(), new CreateUserRequest(
            account, password, displayName, email.isBlank() ? null : email,
            UserEntity.KIND_FRONTEND, siteId));
        if (review) identity.holdFrontendUserForReview(created.id());
        if (!profile.isEmpty()) identity.updateUserProfile(created.id(), profile, created.id());
        return new RegistrationResult(created.username(), displayName,
            review ? UserEntity.STATUS_PENDING : UserEntity.STATUS_ACTIVE, !review);
    }

    private SiteEntity requireSite(UUID siteId) {
        SiteEntity site = sites.findById(siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在"));
        if (!SiteEntity.STATUS_ACTIVE.equals(site.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "站点未启用，不能注册");
        }
        return site;
    }

    private List<RegistrationField> visibleFields(UUID siteId) {
        ContentTypeEntity type = types.findBySiteIdAndCodeOrderByVersionAsc(siteId, SystemContentTypeSeedService.FRONTEND_USER)
            .stream()
            .filter(item -> ContentTypeEntity.STATUS_PUBLISHED.equals(item.getStatus()))
            .reduce((left, right) -> right)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "站点尚未发布会员资料模型"));
        List<FieldDefinitionEntity> defined = fields.findByContentTypeIdOrderBySortOrder(type.getId());
        Set<UUID> shown = layouts.findByContentTypeIdOrderBySortOrder(type.getId()).stream()
            .filter(item -> "FIELD".equals(item.getElementType()))
            .map(FormLayoutEntity::getElementId)
            .collect(java.util.stream.Collectors.toSet());
        List<RegistrationField> out = collectFields(defined, shown);
        if (out.isEmpty() && !shown.isEmpty()) out = collectFields(defined, Set.of());
        return List.copyOf(out);
    }

    private List<RegistrationField> collectFields(List<FieldDefinitionEntity> defined, Set<UUID> shown) {
        List<RegistrationField> out = new ArrayList<>();
        for (FieldDefinitionEntity field : defined) {
            if (field.isReadOnly() || "userId_s".equals(field.getCode())) continue;
            if (!SIMPLE_TYPES.contains(field.getFieldType())) continue;
            if (!shown.isEmpty() && !shown.contains(field.getId())) continue;
            out.add(new RegistrationField(
                field.getCode(), field.getName(), field.getFieldType(), field.isRequired(),
                field.getHelpText() == null ? "" : field.getHelpText(), optionsOf(field.getFieldConfig())));
        }
        return out;
    }

    private List<String> optionsOf(String fieldConfig) {
        if (fieldConfig == null || fieldConfig.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(fieldConfig);
            JsonNode options = root.get("options");
            if (options == null || !options.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            for (JsonNode item : options) {
                if (item.isTextual()) values.add(item.asText());
                else if (item.hasNonNull("value")) values.add(item.get("value").asText());
                else if (item.hasNonNull("label")) values.add(item.get("label").asText());
            }
            return List.copyOf(values);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Object coerce(RegistrationField field, Object raw, String text) {
        try {
            return switch (field.fieldType()) {
                case "BOOLEAN" -> raw instanceof Boolean flag ? flag : Boolean.parseBoolean(text);
                case "INTEGER" -> Integer.valueOf(text);
                case "DECIMAL" -> new java.math.BigDecimal(text);
                case "MULTI_ENUM" -> raw instanceof List<?> list ? list : List.of(text.split(","));
                default -> text;
            };
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, field.name() + "不是有效数字");
        }
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
