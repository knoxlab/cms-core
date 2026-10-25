package com.powercms.cms.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-11-22：通知「事件 → 级别」映射与取值收敛。
 */
class NotificationLevelTest {

    @Test
    void submittedAndCancelledStayInfo() {
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.SUBMITTED)).isEqualTo(NotificationLevel.INFO);
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.CANCELLED)).isEqualTo(NotificationLevel.INFO);
    }

    @Test
    void approvedAndSucceededAreSuccess() {
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.APPROVED)).isEqualTo(NotificationLevel.SUCCESS);
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.SUCCEEDED)).isEqualTo(NotificationLevel.SUCCESS);
    }

    @Test
    void rejectedAndDelegatedAreWarning() {
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.REJECTED)).isEqualTo(NotificationLevel.WARNING);
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.DELEGATED)).isEqualTo(NotificationLevel.WARNING);
    }

    /** 超时提醒是「需要留意」，超时后已自动升级/转派才是「异常且已产生后果」。 */
    @Test
    void timeoutSplitsByEscalation() {
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.TIMEOUT, "REMINDER"))
            .isEqualTo(NotificationLevel.WARNING);
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.TIMEOUT, null))
            .isEqualTo(NotificationLevel.WARNING);
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.TIMEOUT, "ESCALATE"))
            .isEqualTo(NotificationLevel.ERROR);
        assertThat(NotificationLevel.forEvent(WorkflowTaskEvent.TIMEOUT, " escalate "))
            .isEqualTo(NotificationLevel.ERROR);
    }

    @Test
    void unknownOrMissingEventFallsBackToInfo() {
        assertThat(NotificationLevel.forEvent(null)).isEqualTo(NotificationLevel.INFO);
        assertThat(NotificationLevel.forEvent("release.build.failed")).isEqualTo(NotificationLevel.INFO);
        assertThat(NotificationLevel.forEvent("")).isEqualTo(NotificationLevel.INFO);
    }

    @Test
    void normalizeAcceptsKnownLevelsAndRejectsTheRest() {
        assertThat(NotificationLevel.normalize("success")).isEqualTo(NotificationLevel.SUCCESS);
        assertThat(NotificationLevel.normalize(" Warning ")).isEqualTo(NotificationLevel.WARNING);
        assertThat(NotificationLevel.normalize(null)).isEqualTo(NotificationLevel.INFO);
        assertThat(NotificationLevel.normalize("")).isEqualTo(NotificationLevel.INFO);
        assertThat(NotificationLevel.normalize("CRITICAL")).isEqualTo(NotificationLevel.INFO);
        // notification.level 列为 varchar(8)，超长值必须被收敛而不是交给数据库报错
        assertThat(NotificationLevel.normalize("VERY_LONG_LEVEL")).isEqualTo(NotificationLevel.INFO);
    }
}
