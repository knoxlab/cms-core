package com.powercms.cms.workflow;

import com.powercms.cms.workflow.entity.NotificationTemplateEntity;
import com.powercms.cms.workflow.repo.NotificationTemplateRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class NotificationTemplateService {

    private static final Logger log = LoggerFactory.getLogger(NotificationTemplateService.class);

    public static final List<String> KEYS = List.of(
        "task_submitted", "task_approved", "task_rejected", "publish_succeeded",
        "build_failed", "task_timeout", "task_delegated");

    private final NotificationTemplateRepository templates;
    private final Configuration freemarker;

    NotificationTemplateService(NotificationTemplateRepository templates) {
        this.templates = templates;
        this.freemarker = new Configuration(Configuration.VERSION_2_3_32);
        this.freemarker.setDefaultEncoding("UTF-8");
        this.freemarker.setTemplateLoader(new StringTemplateLoader());
    }

    public record TemplateInfo(UUID id, UUID siteId, String key, String subject, String bodyHtml,
                               String bodyText, boolean overlay, Instant updatedAt) {}

    public record Rendered(String subject, String html, String text) {}

    public List<TemplateInfo> list(UUID siteId) {
        return KEYS.stream().map(key -> get(siteId, key)).toList();
    }

    public TemplateInfo get(UUID siteId, String key) {
        var overlay = siteId == null ? java.util.Optional.<NotificationTemplateEntity>empty()
            : templates.findBySiteIdAndTemplateKey(siteId, key);
        var row = overlay.or(() -> templates.findBySiteIdIsNullAndTemplateKey(key))
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "通知模板不存在"));
        return toInfo(row, overlay.isPresent());
    }

    public TemplateInfo upsert(UUID siteId, String key, String subject, String bodyHtml, String bodyText, String actor) {
        if (siteId == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "全局模板不可通过站点 API 覆盖，请传入 siteId 创建站点覆盖");
        }
        if (!KEYS.contains(key)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "未知模板键: " + key);
        }
        var row = templates.findBySiteIdAndTemplateKey(siteId, key).orElseGet(() -> {
            var e = new NotificationTemplateEntity();
            e.setId(UUID.randomUUID());
            e.setSiteId(siteId);
            e.setTemplateKey(key);
            return e;
        });
        row.setSubject(subject == null ? "" : subject);
        row.setBodyHtml(bodyHtml == null ? "" : bodyHtml);
        row.setBodyText(bodyText);
        row.setUpdatedBy(actor);
        row.setUpdatedAt(Instant.now());
        templates.save(row);
        return toInfo(row, true);
    }

    public Rendered preview(UUID siteId, String key, Map<String, Object> sample) {
        var info = get(siteId, key);
        Map<String, Object> model = sample == null || sample.isEmpty() ? sampleContext() : sample;
        return renderStrings(info.subject(), info.bodyHtml(), info.bodyText(), model, info.subject(), info.bodyText());
    }

    public Rendered render(UUID siteId, String key, Map<String, Object> model, String fallbackSubject, String fallbackBody) {
        try {
            var info = get(siteId, key);
            return renderStrings(info.subject(), info.bodyHtml(), info.bodyText(), model, fallbackSubject, fallbackBody);
        } catch (Exception ex) {
            log.warn("template render fallback key={}", key, ex);
            return new Rendered(fallbackSubject, fallbackBody, fallbackBody);
        }
    }

    public static Map<String, Object> sampleContext() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("siteName", "演示站点");
        m.put("contentTitle", "示例文章");
        m.put("taskTitle", "示例文章");
        m.put("submitter", "editor");
        m.put("reviewer", "reviewer");
        m.put("comment", "请核对标题");
        m.put("reason", "请核对标题");
        m.put("scheduledAt", "");
        m.put("previewUrl", "/preview");
        m.put("authoringUrl", "/workflow");
        // DEF-CFG-03：组织级「系统自引用 URL」接入后的邮件深链变量，预览与真实发信同键。
        m.put("portalUrl", "https://portal.example.com");
        m.put("taskUrl", "https://portal.example.com/tasks/00000000-0000-0000-0000-000000000000");
        m.put("failureDetail", "");
        return m;
    }

    private Rendered renderStrings(String subjectTpl, String htmlTpl, String textTpl,
                                   Map<String, Object> model, String fallbackSubject, String fallbackBody) {
        String subject = process(subjectTpl, model, fallbackSubject);
        String html = process(htmlTpl, model, fallbackBody);
        String text = process(textTpl == null || textTpl.isBlank() ? fallbackBody : textTpl, model, fallbackBody);
        return new Rendered(subject, html, text);
    }

    private String process(String source, Map<String, Object> model, String fallback) {
        if (source == null || source.isBlank()) return fallback;
        try {
            Template tpl = new Template("inline", source, freemarker);
            StringWriter out = new StringWriter();
            tpl.process(model, out);
            return out.toString();
        } catch (Exception ex) {
            log.warn("template fragment fallback", ex);
            return fallback;
        }
    }

    private static TemplateInfo toInfo(NotificationTemplateEntity e, boolean overlay) {
        return new TemplateInfo(e.getId(), e.getSiteId(), e.getTemplateKey(), e.getSubject(),
            e.getBodyHtml(), e.getBodyText(), overlay, e.getUpdatedAt());
    }
}
