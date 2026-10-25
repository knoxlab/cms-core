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

import java.util.List;
import java.util.UUID;

/**
 * 统计采集系统组件种子（FR-STAT-001 注入开关）：每站幂等种子
 * {@code system.analytics-tracker}（统计采集）。
 *
 * <p>用户在可视化编辑器把该组件拖入页面投放区并发布后，交付渲染会在
 * {@code </body>} 前自动注入一次 analytics.js 与站点配置（见 delivery.AnalyticsInjector），
 * 删除组件即关闭注入，无需修改任何模板代码。system=true 不可删除、不可改 code/objectType。
 *
 * <p>触发时机：新建站点（{@link SiteCreatedEvent}）与应用启动（{@link ApplicationReadyEvent} 补种存量站点）。
 */
@Component
public class AnalyticsComponentSeedService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsComponentSeedService.class);

    public static final String ANALYTICS_TRACKER = "system.analytics-tracker";
    public static final String TRACKER_NAME = "统计采集";

    private final ContentTypeService typeService;
    private final ContentTypeRepository typeRepo;
    private final SiteRepository siteRepo;

    AnalyticsComponentSeedService(ContentTypeService typeService, ContentTypeRepository typeRepo,
                                 SiteRepository siteRepo) {
        this.typeService = typeService;
        this.typeRepo = typeRepo;
        this.siteRepo = siteRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSiteCreated(SiteCreatedEvent event) {
        try {
            seed(event.siteId());
        } catch (Exception ex) {
            log.warn("analytics tracker seed failed site={} err={}", event.siteId(), ex.getMessage());
        }
    }

    /** 应用启动补种：为存量站点补齐统计采集组件（幂等）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        for (SiteEntity site : siteRepo.findAll()) {
            try {
                if (seed(site.getId()) > 0) {
                    log.info("analytics tracker component backfilled site={}", site.getId());
                }
            } catch (Exception ex) {
                log.warn("analytics tracker backfill failed site={} err={}", site.getId(), ex.getMessage());
            }
        }
    }

    public int seed(UUID siteId) {
        if (typeRepo.existsBySiteIdAndCode(siteId, ANALYTICS_TRACKER)) {
            // 存量类型：幂等补齐 system 标记
            typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, ANALYTICS_TRACKER).stream().findFirst()
                .ifPresent(t -> {
                    if (!t.isSystem()) {
                        t.setSystem(true);
                        typeRepo.save(t);
                        log.info("analytics tracker marked system site={}", siteId);
                    }
                });
            return 0;
        }
        var response = typeService.create(siteId, request());
        ContentTypeEntity type = typeRepo.findById(response.id()).orElseThrow();
        type.setSystem(true);
        typeRepo.save(type);
        typeService.publish(siteId, type.getId(), "analytics-component-seed", null);
        log.info("analytics tracker component seeded site={}", siteId);
        return 1;
    }

    static CreateContentTypeRequest request() {
        return new CreateContentTypeRequest(
            ANALYTICS_TRACKER, TRACKER_NAME,
            "统计采集脚本（analytics.js）：拖入页面投放区并发布后自动注入访问统计，删除即关闭",
            "COMPONENT", null, "BarChartOutlined", null, null, fields(),
            "COMPONENT", null, null, "NONE", null, false, null, null, null, null, null, null, null, null);
    }

    private static List<CreateContentTypeRequest.FieldDefDto> fields() {
        return List.of(
            field("endpoint_s", "采集端点", "自定义采集端点 URL；留空使用同源默认 /api/v1/delivery/analytics/collect", 10),
            field("script_url_s", "脚本地址", "analytics.js 分发地址；留空使用 /api/v1/delivery/analytics/script.js", 20));
    }

    private static CreateContentTypeRequest.FieldDefDto field(String code, String name, String helpText,
                                                              int sortOrder) {
        return new CreateContentTypeRequest.FieldDefDto(
            null, code, name, "STRING", false, sortOrder, null, helpText, null, false, null, null, null,
            false, true, false, false, false, false, false, false);
    }
}
