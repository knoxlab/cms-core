package com.powercms.cms.workflow;

import com.powercms.cms.workflow.entity.NotificationDeliveryEntity;
import com.powercms.cms.workflow.repo.NotificationDeliveryRepository;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * EMAIL channel (spec 8.4). Disabled by default; send failures never roll back workflow transactions.
 */
@Component
public class EmailNotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);

    private final NotificationDeliveryRepository deliveries;
    private final RecipientEmailResolver emails;
    private final MailSenderFactory senderFactory;
    private final NotificationTemplateService templates;
    private final SystemMailContext mailContext;
    private final boolean enabled;
    private final String from;
    private final Configuration freemarker;

    @Autowired
    EmailNotificationChannel(NotificationDeliveryRepository deliveries,
                             ObjectProvider<RecipientEmailResolver> emailResolvers,
                             MailSenderFactory senderFactory,
                             ObjectProvider<NotificationTemplateService> templates,
                             ObjectProvider<SystemMailContext> mailContext,
                             @Value("${powercms.mail.enabled:false}") boolean enabled,
                             @Value("${powercms.mail.from:noreply@powercms.local}") String from) {
        this(deliveries, emailResolvers.getIfAvailable(() -> username -> Optional.empty()),
            senderFactory, templates.getIfAvailable(), mailContext.getIfAvailable(() -> SystemMailContext.EMPTY),
            enabled, from);
    }

    EmailNotificationChannel(NotificationDeliveryRepository deliveries,
                             RecipientEmailResolver emails,
                             MailSenderFactory senderFactory,
                             boolean enabled,
                             String from) {
        this(deliveries, emails, senderFactory, null, SystemMailContext.EMPTY, enabled, from);
    }

    EmailNotificationChannel(NotificationDeliveryRepository deliveries,
                             RecipientEmailResolver emails,
                             MailSenderFactory senderFactory,
                             NotificationTemplateService templates,
                             boolean enabled,
                             String from) {
        this(deliveries, emails, senderFactory, templates, SystemMailContext.EMPTY, enabled, from);
    }

    EmailNotificationChannel(NotificationDeliveryRepository deliveries,
                             RecipientEmailResolver emails,
                             MailSenderFactory senderFactory,
                             NotificationTemplateService templates,
                             SystemMailContext mailContext,
                             boolean enabled,
                             String from) {
        this.deliveries = deliveries;
        this.emails = emails;
        this.senderFactory = senderFactory;
        this.templates = templates;
        this.mailContext = mailContext == null ? SystemMailContext.EMPTY : mailContext;
        this.enabled = enabled;
        this.from = from;
        this.freemarker = mailTemplates();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void send(WorkflowTaskEvent event, String recipientUsername, String title, String body, String idempotencyKey) {
        if (!enabled || event == null) return;
        String key = "email:" + idempotencyKey;
        if (deliveries.existsByIdempotencyKeyAndChannelAndStatus(key, NotificationDeliveryEntity.CHANNEL_EMAIL,
            NotificationDeliveryEntity.SENT)) {
            deliveries.save(NotificationDeliveryEntity.of(event.eventType(), key,
                NotificationDeliveryEntity.CHANNEL_EMAIL, recipientUsername,
                NotificationDeliveryEntity.SKIPPED_DUPLICATE, null));
            return;
        }
        Optional<String> to = emails.emailFor(recipientUsername);
        if (to.isEmpty()) {
            log.info("NOTIFICATION_NO_RECIPIENT event={} user={}", event.eventType(), recipientUsername);
            deliveries.save(NotificationDeliveryEntity.of(event.eventType(), key,
                NotificationDeliveryEntity.CHANNEL_EMAIL, recipientUsername,
                NotificationDeliveryEntity.SKIPPED, "no email"));
            return;
        }
        try {
            String subject;
            String text;
            if (templates != null) {
                Map<String, Object> model = eventModel(event);
                var rendered = templates.render(event.siteId(), templateKey(event.eventType()), model, title, body);
                subject = rendered.subject();
                text = rendered.text() == null || rendered.text().isBlank() ? rendered.html() : rendered.text();
            } else {
                subject = render("subject", event, title);
                text = render("body", event, body);
            }
            JavaMailSender sender = senderFactory.create();
            SimpleMailMessage message = new SimpleMailMessage();
            // DEF-CFG-03：组织配置的发件地址优先；未配置才回退静态 powercms.mail.from。
            message.setFrom(mailContext.fromAddress().orElse(from));
            message.setTo(to.get());
            message.setSubject(subject);
            message.setText(text);
            sender.send(message);
            deliveries.save(NotificationDeliveryEntity.of(event.eventType(), key,
                NotificationDeliveryEntity.CHANNEL_EMAIL, to.get(),
                NotificationDeliveryEntity.SENT, null));
        } catch (Exception ex) {
            log.error("email channel failed event={} user={}", event.eventType(), recipientUsername, ex);
            deliveries.save(NotificationDeliveryEntity.of(event.eventType(), key,
                NotificationDeliveryEntity.CHANNEL_EMAIL, to.get(),
                NotificationDeliveryEntity.FAILED, truncate(ex.getMessage())));
        }
    }

    private String render(String kind, WorkflowTaskEvent event, String fallback) {
        try {
            Template tpl = freemarker.getTemplate(kind);
            Map<String, Object> model = eventModel(event);
            model.put("fallback", fallback);
            StringWriter out = new StringWriter();
            tpl.process(model, out);
            return out.toString();
        } catch (Exception ex) {
            log.warn("mail template fallback kind={} event={}", kind, event.eventType(), ex);
            return fallback;
        }
    }

    /**
     * 邮件模板变量。DEF-CFG-03：补 {@code portalUrl} / {@code taskUrl}，让组织配置的
     * 「系统自引用 URL」真正决定邮件里的**绝对**深链。
     *
     * <p>两个键**恒有值**（未配置时为空串）而非缺键：Freemarker 对缺键做
     * {@code ?has_content} 判定会抛 {@code InvalidReferenceException}，空串则安全求值为 false。
     */
    private Map<String, Object> eventModel(WorkflowTaskEvent event) {
        Map<String, Object> model = new LinkedHashMap<>();
        String portalUrl = mailContext.portalUrl().orElse("");
        model.put("siteName", "");
        model.put("taskTitle", event.contentTitle());
        model.put("contentTitle", event.contentTitle());
        model.put("submitter", nullToEmpty(event.submitter()));
        model.put("reviewer", nullToEmpty(event.actor()));
        model.put("comment", nullToEmpty(event.comment()));
        model.put("reason", nullToEmpty(event.comment()));
        model.put("portalUrl", portalUrl);
        model.put("taskUrl", taskUrl(portalUrl, event.taskId()));
        return model;
    }

    /** 绝对深链；无 {@code portalUrl} 或任务 id 时返回空串（模板据此省略整行）。 */
    private static String taskUrl(String portalUrl, UUID taskId) {
        if (portalUrl == null || portalUrl.isBlank() || taskId == null) return "";
        return trimTrailingSlash(portalUrl) + "/tasks/" + taskId;
    }

    private static String trimTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') end--;
        return url.substring(0, end);
    }

    private static String templateKey(String eventType) {
        if (eventType == null) return "task_submitted";
        return switch (eventType) {
            case WorkflowTaskEvent.APPROVED -> "task_approved";
            case WorkflowTaskEvent.REJECTED -> "task_rejected";
            case WorkflowTaskEvent.SUCCEEDED -> "publish_succeeded";
            case WorkflowTaskEvent.DELEGATED, WorkflowTaskEvent.CANCELLED -> "task_delegated";
            case WorkflowTaskEvent.TIMEOUT -> "task_timeout";
            default -> "task_submitted";
        };
    }

    private static Configuration mailTemplates() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setDefaultEncoding("UTF-8");
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("subject", "${fallback}");
        loader.putTemplate("body",
            "内容《${contentTitle}》\n提交人：${submitter}\n处理人：${reviewer}\n${fallback}\n"
                + "<#if taskUrl?has_content>查看任务：${taskUrl}\n</#if>意见：${comment}");
        cfg.setTemplateLoader(loader);
        return cfg;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    /**
     * Builds a JavaMail sender from {@code powercms.mail.*} so tests do not depend on Boot auto-config.
     */
    @Component
    static class MailSenderFactory {
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final boolean smtpAuth;
        private final boolean starttls;

        MailSenderFactory(@Value("${powercms.mail.host:localhost}") String host,
                          @Value("${powercms.mail.port:25}") int port,
                          @Value("${powercms.mail.username:}") String username,
                          @Value("${powercms.mail.password:}") String password,
                          @Value("${powercms.mail.smtp-auth:false}") boolean smtpAuth,
                          @Value("${powercms.mail.starttls:true}") boolean starttls) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.smtpAuth = smtpAuth;
            this.starttls = starttls;
        }

        public JavaMailSender create() {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);
            if (username != null && !username.isBlank()) {
                sender.setUsername(username);
                sender.setPassword(password);
            }
            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", Boolean.toString(smtpAuth));
            props.put("mail.smtp.starttls.enable", Boolean.toString(starttls));
            props.put("mail.smtp.connectiontimeout", "5000");
            props.put("mail.smtp.timeout", "5000");
            return sender;
        }
    }
}
