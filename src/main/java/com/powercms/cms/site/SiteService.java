package com.powercms.cms.site;

import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.site.dto.CreateSiteRequest;
import com.powercms.cms.site.dto.SiteResponse;
import com.powercms.cms.site.entity.EnvironmentEntity;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.EnvironmentRepository;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 站点服务：创建、查询、冻结/归档、环境管理（FR-SITE-001～006）。
 * 创建后自动生成 preview 环境，并发布 {@link SiteCreatedEvent} 供站点级初始化（媒体类型种子等）消费。
 */
@Service
@Transactional
public class SiteService {

    private final SiteRepository siteRepo;
    private final EnvironmentRepository envRepo;
    private final IdentityService identityService;
    private final ApplicationEventPublisher eventPublisher;

    SiteService(SiteRepository siteRepo, EnvironmentRepository envRepo, IdentityService identityService,
                ApplicationEventPublisher eventPublisher) {
        this.siteRepo = siteRepo;
        this.envRepo = envRepo;
        this.identityService = identityService;
        this.eventPublisher = eventPublisher;
    }

    public SiteResponse create(UUID organizationId, CreateSiteRequest req, UUID actorUserId) {
        if (siteRepo.existsBySiteKey(req.siteKey())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "站点 Key 已存在");
        }
        SiteEntity site = new SiteEntity();
        site.setOrganizationId(organizationId);
        site.setSiteKey(req.siteKey());
        site.setName(req.name());
        site.setDescription(req.description());
        site.setDefaultLocale(req.defaultLocale() != null ? req.defaultLocale() : "zh-CN");
        site.setTimezone(req.timezone() != null ? req.timezone() : "Asia/Shanghai");
        site = siteRepo.save(site);

        envRepo.save(EnvironmentEntity.create(site.getId(), "preview", EnvironmentEntity.TYPE_PREVIEW));
        envRepo.save(EnvironmentEntity.create(site.getId(), "staging", EnvironmentEntity.TYPE_STAGING));
        envRepo.save(EnvironmentEntity.create(site.getId(), "production", EnvironmentEntity.TYPE_PRODUCTION));

        UUID adminUserId = req.initialAdminUserId() != null ? req.initialAdminUserId() : actorUserId;
        if (adminUserId != null) {
            identityService.bindInitialSiteAdmin(adminUserId, site.getId());
        }

        eventPublisher.publishEvent(new SiteCreatedEvent(site.getId(), adminUserId));

        return toResponse(site);
    }

    public SiteResponse getById(UUID siteId) {
        return toResponse(requireSite(siteId));
    }

    /**
     * 站点列表（FR-SITE-LC-001/005）。
     * <p>默认返回进行中站点（ACTIVE + FROZEN）——冻结站点须保留在列表中，否则无法归档；
     * 归档站点默认隐藏，调用方可传 {@code status=ARCHIVED} 显式查询。</p>
     */
    public List<SiteResponse> list(UUID organizationId, String status) {
        List<SiteEntity> sites;
        if (status != null && !status.isBlank()) {
            sites = siteRepo.findByOrganizationIdAndStatus(organizationId, status);
        } else {
            sites = siteRepo.findByOrganizationIdAndStatusIn(
                organizationId, List.of(SiteEntity.STATUS_ACTIVE, SiteEntity.STATUS_FROZEN));
        }
        return sites.stream().map(this::toResponse).toList();
    }

    public void freeze(UUID siteId) {
        SiteEntity site = requireSite(siteId);
        site.setStatus(SiteEntity.STATUS_FROZEN);
        siteRepo.save(site);
    }

    public void unarchive(UUID siteId) {
        SiteEntity site = requireSite(siteId);
        site.setStatus(SiteEntity.STATUS_ACTIVE);
        siteRepo.save(site);
    }

    public void archive(UUID siteId) {
        SiteEntity site = requireSite(siteId);
        if (SiteEntity.STATUS_FROZEN.equals(site.getStatus())) {
            site.setStatus(SiteEntity.STATUS_ARCHIVED);
            siteRepo.save(site);
            return;
        }
        // 有运行任务时归档需阻断
        throw new BusinessException(ErrorCodes.CONFLICT, "站点未冻结，不能直接归档");
    }

    SiteEntity requireSite(UUID siteId) {
        return siteRepo.findById(siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在"));
    }

    /** 站点所属组织 ID（FR-AGT-MCP-007：Agent 站点清单按调用者可访问范围收敛到组织内）。 */
    public UUID organizationIdOf(UUID siteId) {
        return requireSite(siteId).getOrganizationId();
    }

    /**
     * 站点基础配置更新（FR-AGT-SITE-003 白名单的可写子集）：名称 / 描述 / 默认语言 / 时区。
     * 仅更新显式传入的字段；名称、语言、时区不允许置空。域名、发布目标、权限策略等核心配置不在本方法范围。
     */
    public SiteResponse updateProfile(UUID siteId, String name, String description,
                                      String defaultLocale, String timezone) {
        SiteEntity site = requireSite(siteId);
        if (name != null) {
            if (name.isBlank()) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "站点名称不能为空");
            }
            site.setName(name.trim());
        }
        if (description != null) {
            site.setDescription(description.isBlank() ? null : description.trim());
        }
        if (defaultLocale != null) {
            if (defaultLocale.isBlank()) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "默认语言不能为空");
            }
            site.setDefaultLocale(defaultLocale.trim());
        }
        if (timezone != null) {
            if (timezone.isBlank()) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "时区不能为空");
            }
            site.setTimezone(timezone.trim());
        }
        return toResponse(siteRepo.save(site));
    }

    public boolean accessGuidanceEnabled(UUID siteId) {
        return requireSite(siteId).isAccessGuidanceEnabled();
    }

    public boolean setAccessGuidance(UUID siteId, boolean enabled) {
        SiteEntity site = requireSite(siteId);
        site.setAccessGuidanceEnabled(enabled);
        siteRepo.save(site);
        return site.isAccessGuidanceEnabled();
    }

    private SiteResponse toResponse(SiteEntity site) {
        List<EnvironmentEntity> envs = envRepo.findBySiteId(site.getId());
        List<SiteResponse.EnvInfo> envInfos = envs.stream()
            .map(e -> new SiteResponse.EnvInfo(e.getId(), e.getName(), e.getType()))
            .toList();
        return SiteResponse.from(site, envInfos);
    }
}
