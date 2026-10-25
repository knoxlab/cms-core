package com.powercms.cms.model;

import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.SiteCreatedEvent;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 预置用户内容类型种子（spec 5.2 / 8.9）：每站幂等种子
 * {@code system.user-profile}（后台用户资料）与 {@code system.frontend-user}（前台会员资料）。
 *
 * <p>二者均为 SHARED、system=true（不可删除、不可改 code/objectType），字段可经内容类型编辑器扩展。
 * 触发时机：新建站点（{@link SiteCreatedEvent}）与应用启动（{@link ApplicationReadyEvent} 补种存量站点）。
 */
@Component
public class SystemContentTypeSeedService {

    private static final Logger log = LoggerFactory.getLogger(SystemContentTypeSeedService.class);

    public static final String USER_PROFILE = "system.user-profile";
    public static final String FRONTEND_USER = "system.frontend-user";
    public static final String PROFILE_TEMPLATE = "/system/user-profile.ftl";

    private final ContentTypeService typeService;
    private final ContentTypeRepository typeRepo;
    private final SiteRepository siteRepo;

    SystemContentTypeSeedService(ContentTypeService typeService, ContentTypeRepository typeRepo,
                                 SiteRepository siteRepo) {
        this.typeService = typeService;
        this.typeRepo = typeRepo;
        this.siteRepo = siteRepo;
    }

    public record Seed(String code, String name, String description) {}

    public static List<Seed> seeds() {
        return List.of(
            new Seed(USER_PROFILE, "后台用户资料", "后台员工（kind=BACKEND）身份资料，与 sys_user 1:1（spec 5.2）"),
            new Seed(FRONTEND_USER, "前台会员资料", "前台会员（kind=FRONTEND）身份资料，与 sys_user 1:1（spec 5.2）"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSiteCreated(SiteCreatedEvent event) {
        try {
            seed(event.siteId());
        } catch (Exception ex) {
            log.warn("system content type seed failed site={} err={}", event.siteId(), ex.getMessage());
        }
    }

    /** 应用启动补种：为存量站点补齐系统用户内容类型（幂等）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        for (SiteEntity site : siteRepo.findAll()) {
            try {
                int created = seed(site.getId());
                if (created > 0) {
                    log.info("system content types backfilled site={} created={}", site.getId(), created);
                }
            } catch (Exception ex) {
                log.warn("system content type backfill failed site={} err={}", site.getId(), ex.getMessage());
            }
        }
    }

    public int seed(UUID siteId) {
        int created = 0;
        for (Seed seed : seeds()) {
            if (typeRepo.existsBySiteIdAndCode(siteId, seed.code())) {
                // 存量类型：幂等补齐 system 标记
                typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, seed.code()).stream().findFirst()
                    .ifPresent(t -> {
                        if (!t.isSystem()) {
                            t.setSystem(true);
                            typeRepo.save(t);
                            log.info("system content type marked system site={} code={}", siteId, seed.code());
                        }
                    });
                continue;
            }
            var response = typeService.create(siteId, request(seed));
            ContentTypeEntity type = typeRepo.findById(response.id()).orElseThrow();
            type.setSystem(true);
            typeRepo.save(type);
            typeService.publish(siteId, type.getId(), "system-content-type-seed", null);
            created++;
            log.info("system content type seeded site={} code={}", siteId, seed.code());
        }
        return created;
    }

    static CreateContentTypeRequest request(Seed seed) {
        List<CreateContentTypeRequest.FieldDefDto> fields = new ArrayList<>();
        fields.add(field("userId_s", "用户 ID", true, true));
        fields.add(field("displayName_s", "显示名", true, false));
        fields.add(field("email_s", "邮箱", false, false));
        fields.add(field("avatarUrl_s", "头像", false, false));
        fields.add(field("locale_s", "语言", false, false));
        fields.add(field("timezone_s", "时区", false, false));
        fields.add(field("phone_s", "电话", false, false));
        return new CreateContentTypeRequest(
            seed.code(), seed.name(), seed.description(),
            "COMPONENT", PROFILE_TEMPLATE, "UserOutlined", null, null, fields,
            "SHARED", null, null, "NONE", null, false, null, null, null, null, null, null, null, null);
    }

    private static CreateContentTypeRequest.FieldDefDto field(String code, String name, boolean required,
                                                              boolean readOnly) {
        return new CreateContentTypeRequest.FieldDefDto(
            null, code, name, "STRING", required, 0, null, null, null, readOnly, null, null, null,
            false, true, false, false, false, false, false, false);
    }
}
