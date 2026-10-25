package com.powercms.cms.workflow;

import com.powercms.cms.workflow.entity.NotificationEntity;
import com.powercms.cms.workflow.entity.NotificationPreferenceEntity;
import com.powercms.cms.workflow.repo.NotificationPreferenceRepository;
import com.powercms.cms.workflow.repo.NotificationRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifRepo;
    private final NotificationPreferenceRepository prefRepo;

    /** 版本能力门禁端口；单测直接构造时为 null，此时跳过校验（同 WorkflowService 口径）。 */
    private WorkflowEntitlementPort entitlement;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEntitlement(WorkflowEntitlementPort entitlement) {
        this.entitlement = entitlement;
    }

    NotificationService(NotificationRepository notifRepo, NotificationPreferenceRepository prefRepo) {
        this.notifRepo = notifRepo;
        this.prefRepo = prefRepo;
    }

    public record NotifInfo(UUID id, String type, String title, String body, boolean read,
                            String level, String link, java.time.Instant createdAt) {
        public static NotifInfo from(NotificationEntity n) {
            return new NotifInfo(n.getId(), n.getType(), n.getTitle(), n.getBody(),
                n.getReadAt() != null, n.getLevel(), n.getLink(), n.getCreatedAt());
        }
    }

    /**
     * 幂等写入站内通知，级别按 {@link NotificationLevel#INFO} 处理（兼容旧调用方）。相同
     * idempotencyKey 只保留一条。
     */
    public NotificationEntity notifyOnce(String recipient, String type, String title, String body,
                                         String relatedType, UUID relatedId, String idempotencyKey, String link) {
        return notifyOnce(recipient, type, title, body, relatedType, relatedId, idempotencyKey, link,
            NotificationLevel.INFO);
    }

    /**
     * 幂等写入站内通知，并显式指定级别。
     *
     * <p>必须使用 {@link Propagation#REQUIRES_NEW}：本方法由
     * {@link WorkflowNotificationListener} 在 {@code AFTER_COMMIT} 回调中调用，此时外层事务
     * 已提交但连接/同步器尚未清理，若沿用默认的 {@code REQUIRED} 会「加入」该已提交事务，
     * 导致 INSERT 既不 flush 也不 commit，通知静默丢失（DEF-11-01）。</p>
     *
     * <p>级别由调用方按「事件 → 级别」映射给出（见 {@link NotificationLevel#forEvent(String, String)}，
     * DEF-11-22）：此前实现固定写 {@code INFO}，前端四色标签中的
     * {@code SUCCESS}/{@code WARNING}/{@code ERROR} 无任何真实产出路径。传入非法值会被
     * {@link NotificationLevel#normalize(String)} 收敛为 {@code INFO}，不写坏列约束。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationEntity notifyOnce(String recipient, String type, String title, String body,
                                         String relatedType, UUID relatedId, String idempotencyKey, String link,
                                         String level) {
        if (recipient == null || recipient.isBlank() || idempotencyKey == null) return null;
        if (notifRepo.findByIdempotencyKey(idempotencyKey).isPresent()) return null;
        try {
            NotificationEntity n = NotificationEntity.create(recipient, type, title, body, relatedType, relatedId);
            n.setIdempotencyKey(idempotencyKey);
            n.setLink(link);
            n.setGroupId(relatedId);
            n.setLevel(NotificationLevel.normalize(level));
            return notifRepo.save(n);
        } catch (DataIntegrityViolationException ex) {
            log.debug("duplicate notification skipped key={}", idempotencyKey);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<NotifInfo> list(String username, boolean unreadOnly) {
        var rows = unreadOnly
            ? notifRepo.findByRecipientAndReadAtIsNull(username)
            : notifRepo.findByRecipientOrderByCreatedAtDesc(username);
        return rows.stream().map(NotifInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(String username) {
        return notifRepo.countByRecipientAndReadAtIsNull(username);
    }

    public void markRead(UUID notifId) {
        notifRepo.findById(notifId).ifPresent(n -> {
            n.setReadAt(Instant.now());
            notifRepo.save(n);
        });
    }

    public int markAllRead(String username) {
        var unread = notifRepo.findByRecipientAndReadAtIsNull(username);
        Instant now = Instant.now();
        unread.forEach(n -> n.setReadAt(now));
        notifRepo.saveAll(unread);
        return unread.size();
    }

    public record PrefInfo(String eventType, String channel, boolean enabled) {}

    public static final List<String> PREF_EVENTS = List.of(
        WorkflowTaskEvent.SUBMITTED, WorkflowTaskEvent.APPROVED, WorkflowTaskEvent.REJECTED,
        WorkflowTaskEvent.CANCELLED, WorkflowTaskEvent.DELEGATED, WorkflowTaskEvent.SUCCEEDED,
        WorkflowTaskEvent.TIMEOUT);
    public static final List<String> PREF_CHANNELS = List.of(
        NotificationPreferenceEntity.CHANNEL_IN_APP, NotificationPreferenceEntity.CHANNEL_EMAIL);

    @Transactional(readOnly = true)
    public boolean channelEnabled(String username, String eventType, String channel) {
        if (username == null || eventType == null || channel == null) return true;
        return prefRepo.findByUsernameAndEventTypeAndChannel(username, eventType, channel)
            .map(NotificationPreferenceEntity::isEnabled)
            .orElse(true);
    }

    @Transactional(readOnly = true)
    public List<PrefInfo> preferences(String username) {
        var saved = prefRepo.findByUsername(username);
        return PREF_EVENTS.stream().flatMap(event -> PREF_CHANNELS.stream().map(channel -> {
            boolean enabled = saved.stream()
                .filter(p -> event.equals(p.getEventType()) && channel.equals(p.getChannel()))
                .findFirst()
                .map(NotificationPreferenceEntity::isEnabled)
                .orElse(true);
            return new PrefInfo(event, channel, enabled);
        })).toList();
    }

    public List<PrefInfo> savePreferences(String username, List<PrefInfo> items) {
        if (items == null) return preferences(username);
        for (var item : items) {
            if (item == null || item.eventType() == null || item.channel() == null) continue;
            // 版本分级（矩阵 E3）：站内 + SMTP 邮件为全版本基础渠道；IM/短信等扩展渠道属专业版及以上
            // （NOTIFY_CHANNELS）。非基础渠道先过能力门禁（社区版 403），再拒绝尚未实现的渠道值——
            // 该检查同时修复了此前「任意 channel 字符串都能落库」的白名单缺口。
            if (!NotificationPreferenceEntity.CHANNEL_IN_APP.equals(item.channel())
                    && !NotificationPreferenceEntity.CHANNEL_EMAIL.equals(item.channel())) {
                if (entitlement != null) {
                    entitlement.requireCapability("NOTIFY_CHANNELS");
                }
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "未注册的通知渠道：" + item.channel());
            }
            var row = prefRepo.findByUsernameAndEventTypeAndChannel(username, item.eventType(), item.channel())
                .orElseGet(() -> NotificationPreferenceEntity.of(username, item.eventType(), item.channel(), true));
            row.setEnabled(item.enabled());
            prefRepo.save(row);
        }
        return preferences(username);
    }
}
