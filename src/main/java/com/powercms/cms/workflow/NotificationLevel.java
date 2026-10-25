package com.powercms.cms.workflow;

import java.util.Locale;
import java.util.Set;

/**
 * 站内通知级别（DEF-11-22）。
 *
 * <p>规范 {@code docs/workflow-notification-specification.md} §8.3 此前只给出枚举
 * {@code INFO / WARNING / ERROR}，未定义「哪个事件用哪个级别」；实现里 {@code notifyOnce}
 * 长期硬编码 {@code INFO}，前端四色标签中的 {@code SUCCESS} / {@code WARNING} / {@code ERROR}
 * 因此没有任何真实产出路径（只能靠直连造数验证渲染）。</p>
 *
 * <p>本类把「事件 → 级别」的映射收敛到这一处常量，作为规范 §8.3 映射表的代码载体；
 * 通知写入前统一经 {@link #normalize(String)} 收敛，避免外部传入未知值写坏
 * {@code notification.level}（{@code varchar(8)}）。</p>
 */
public final class NotificationLevel {

    /** 普通进展（提交审核、任务取消等），无需额外关注。 */
    public static final String INFO = "INFO";
    /** 正向完成（审核通过、按计划发布完成）。 */
    public static final String SUCCESS = "SUCCESS";
    /** 需要收件人处理或留意（驳回、委派、超时提醒）。 */
    public static final String WARNING = "WARNING";
    /** 异常且已产生后果（超时后自动升级/转派）。 */
    public static final String ERROR = "ERROR";

    /** 合法级别集合，与前端 {@code LEVEL_COLORS} 及规范 §8.3 保持一致。 */
    public static final Set<String> ALL = Set.of(INFO, SUCCESS, WARNING, ERROR);

    /** 超时动作键：超时后已自动转派/升级（{@code WorkflowService.processTimeout}）。 */
    private static final String TIMEOUT_ACTION_ESCALATE = "ESCALATE";

    private NotificationLevel() {}

    /**
     * 事件 → 级别映射（规范 §8.3）。
     *
     * <table>
     *   <tr><th>事件</th><th>级别</th></tr>
     *   <tr><td>{@code review-task.submitted} / {@code review-task.cancelled}</td><td>{@link #INFO}</td></tr>
     *   <tr><td>{@code review-task.approved} / {@code review-task.succeeded}</td><td>{@link #SUCCESS}</td></tr>
     *   <tr><td>{@code review-task.rejected} / {@code review-task.delegated}</td><td>{@link #WARNING}</td></tr>
     *   <tr><td>{@code workflow.timeout.warning}（提醒）</td><td>{@link #WARNING}</td></tr>
     *   <tr><td>{@code workflow.timeout.warning}（{@code ESCALATE}，已自动升级/转派）</td><td>{@link #ERROR}</td></tr>
     * </table>
     *
     * @param eventType         {@link WorkflowTaskEvent} 的事件类型
     * @param timeoutActionKey  仅超时事件使用：流程定义的超时动作键，{@code ESCALATE} 视为已升级；
     *                          其他事件传 {@code null}
     */
    public static String forEvent(String eventType, String timeoutActionKey) {
        if (eventType == null) return INFO;
        return switch (eventType) {
            case WorkflowTaskEvent.APPROVED, WorkflowTaskEvent.SUCCEEDED -> SUCCESS;
            case WorkflowTaskEvent.REJECTED, WorkflowTaskEvent.DELEGATED -> WARNING;
            case WorkflowTaskEvent.TIMEOUT -> isEscalated(timeoutActionKey) ? ERROR : WARNING;
            default -> INFO;
        };
    }

    /** 事件 → 级别映射（无超时动作键上下文，超时事件按提醒处理）。 */
    public static String forEvent(String eventType) {
        return forEvent(eventType, null);
    }

    /** 超时动作键是否表示「已自动升级/转派」。 */
    public static boolean isEscalated(String timeoutActionKey) {
        return timeoutActionKey != null
            && TIMEOUT_ACTION_ESCALATE.equalsIgnoreCase(timeoutActionKey.trim());
    }

    /**
     * 收敛级别取值：空白或未知值回退为 {@link #INFO}，大小写与首尾空白归一为枚举大写，
     * 超出 {@code notification.level} 列宽（8）的值同样回退。
     */
    public static String normalize(String level) {
        if (level == null) return INFO;
        String v = level.trim().toUpperCase(Locale.ROOT);
        return v.length() <= 8 && ALL.contains(v) ? v : INFO;
    }
}
