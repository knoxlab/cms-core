package com.powercms.cms.identity;

import com.powercms.cms.identity.dto.OrganizationSettingsResponse;
import com.powercms.cms.identity.dto.UpdateOrganizationSettingsRequest;
import com.powercms.cms.identity.entity.OrganizationSettingsEntity;
import com.powercms.cms.identity.repo.OrganizationRepository;
import com.powercms.cms.identity.repo.OrganizationSettingsRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 组织安全与品牌配置（spec 5.5 / 8.8 / 9.7）：loginIdentifier、品牌、发件配置。
 */
@Service
@Transactional
public class OrganizationSettingsService {

    private final OrganizationSettingsRepository settingsRepo;
    private final OrganizationRepository orgRepo;
    private final UserRepository userRepo;

    public OrganizationSettingsService(OrganizationSettingsRepository settingsRepo,
                                       OrganizationRepository orgRepo, UserRepository userRepo) {
        this.settingsRepo = settingsRepo;
        this.orgRepo = orgRepo;
        this.userRepo = userRepo;
    }

    /**
     * 首期单组织：当前组织 = {@link OrganizationRepository#findAllIds()} 的第一行
     * （createdAt 升序，tie-break by id），**无则返回 null**。
     *
     * <p>这一口径必须与写入侧 {@code IdentityServiceImpl.ensureOrganization} 同源：
     * 两者若各自取「不同的第一行」，组织设置就会写在一个组织、被消费方从另一个组织读走（DEF-CFG-01）。
     */
    public UUID defaultOrganizationId() {
        List<UUID> ids = orgRepo.findAllIds();
        return ids.isEmpty() ? null : ids.get(0);
    }

    public OrganizationSettingsEntity settings(UUID organizationId) {
        return settingsRepo.findById(organizationId)
            .orElseGet(() -> OrganizationSettingsEntity.defaults(organizationId));
    }

    public String loginIdentifier(UUID organizationId) {
        if (organizationId == null) return OrganizationSettingsEntity.LOGIN_IDENTIFIER_USERNAME;
        String value = settings(organizationId).getLoginIdentifier();
        return value == null || value.isBlank()
            ? OrganizationSettingsEntity.LOGIN_IDENTIFIER_USERNAME : value;
    }

    public OrganizationSettingsResponse get(UUID organizationId) {
        OrganizationSettingsEntity s = settings(organizationId);
        return toResponse(s);
    }

    /**
     * 公开品牌信息（spec 5.5）：供未登录的登录页读取，仅暴露非敏感字段。
     * mailDomain / fromEmail 不对外公开（暴露发信基础设施信息）。
     *
     * <p><b>loginIdentifier 必须公开</b>（DEF-CFG-02）：登录页要按登录标识模式渲染正确的
     * 输入框标签（用户名 / 邮箱）并决定是否预填 —— 它决定的是「前端该问用户要什么」，
     * 不是秘密；后端 {@code resolveLoginUser} 仍按同一取值**独立**裁决，前端只做展示对齐，
     * 不构成鉴权绕过。
     */
    public OrganizationSettingsResponse publicBranding() {
        UUID orgId = defaultOrganizationId();
        if (orgId == null) return new OrganizationSettingsResponse(null, null, null, null, null, null, null);
        OrganizationSettingsEntity s = settings(orgId);
        return new OrganizationSettingsResponse(s.getLoginIdentifier(), s.getPortalUrl(), null, null,
            s.getDefaultLanguage(), s.getDefaultTimezone(), s.getBranding());
    }

    public OrganizationSettingsResponse update(UUID organizationId, UpdateOrganizationSettingsRequest req) {
        if (!orgRepo.existsById(organizationId)) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "组织不存在");
        }
        OrganizationSettingsEntity settings = settings(organizationId);
        if (req.loginIdentifier() != null) settings.setLoginIdentifier(req.loginIdentifier().trim().toUpperCase());
        if (req.portalUrl() != null) settings.setPortalUrl(req.portalUrl().trim());
        if (req.mailDomain() != null) settings.setMailDomain(req.mailDomain().trim());
        if (req.fromEmail() != null) settings.setFromEmail(req.fromEmail().trim());
        if (req.defaultLanguage() != null) settings.setDefaultLanguage(req.defaultLanguage().trim());
        if (req.defaultTimezone() != null) settings.setDefaultTimezone(req.defaultTimezone().trim());
        if (req.branding() != null) settings.setBranding(req.branding());
        settingsRepo.save(settings);
        return toResponse(settings);
    }

    /**
     * 按组织 loginIdentifier 解析登录标识（spec 8.8 / AC-IAM-019）：
     * EMAIL 时仅按邮箱、USERNAME 时仅按用户名，不做跨模式回退。
     */
    public com.powercms.cms.identity.entity.UserEntity resolveLoginUser(UUID organizationId, String loginId) {
        String identifier = loginIdentifier(organizationId);
        if (OrganizationSettingsEntity.LOGIN_IDENTIFIER_EMAIL.equals(identifier)) {
            return userRepo.findByEmailIgnoreCase(loginId).orElse(null);
        }
        return userRepo.findByUsername(loginId).orElse(null);
    }

    private static OrganizationSettingsResponse toResponse(OrganizationSettingsEntity s) {
        return new OrganizationSettingsResponse(
            s.getLoginIdentifier(), s.getPortalUrl(), s.getMailDomain(), s.getFromEmail(),
            s.getDefaultLanguage(), s.getDefaultTimezone(), s.getBranding());
    }
}
