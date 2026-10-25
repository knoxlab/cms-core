package com.powercms.cms.content.web;

import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FR-CV-VER-009：时间机器时间点解析——只接受**带时区的绝对时刻**。
 *
 * <p>裸本地时间（{@code 2026-09-18T10:00:00}）必须被拒绝：服务端无法知道用户指的是哪个时区，
 * 猜错会让整个快照静默偏移几小时，用户看到的「历史」根本不是他选的那一刻。</p>
 */
class TimeMachineControllerTest {

    @Test
    void acceptsInstantWithZuluOffset() {
        assertEquals(Instant.parse("2026-09-18T10:00:00Z"), TimeMachineController.parseInstant("2026-09-18T10:00:00Z"));
    }

    @Test
    void acceptsMillisAndOffsetForm() {
        assertEquals(Instant.parse("2026-09-18T02:00:00.000Z"),
            TimeMachineController.parseInstant("2026-09-18T10:00:00.000+08:00"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals(Instant.parse("2026-09-18T10:00:00Z"), TimeMachineController.parseInstant("  2026-09-18T10:00:00Z  "));
    }

    @Test
    void rejectsLocalDateTimeWithoutZone() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> TimeMachineController.parseInstant("2026-09-18T10:00:00"));
        assertEquals("VALIDATION_FAILED", ex.getCode());
    }

    @Test
    void rejectsGarbage() {
        assertEquals("VALIDATION_FAILED", assertThrows(BusinessException.class,
            () -> TimeMachineController.parseInstant("昨天下午")).getCode());
    }

    @Test
    void blankMeansNow() {
        assertNull(TimeMachineController.parseInstant(null));
        assertNull(TimeMachineController.parseInstant(""));
        assertNull(TimeMachineController.parseInstant("   "));
    }
}
