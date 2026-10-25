package com.powercms.cms.content.web;

import com.powercms.cms.content.ContentTimeMachineService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * FR-CV-VER-009：时间机器只读快照查询（`GET /sites/{siteId}/time-machine?at=...`）。
 *
 * <p>只读：仅提供 GET。视图侧的写保护由 {@code TimeMachineReadOnlyInterceptor} 负责——
 * 只读浏览期间客户端带头声明，服务端据此拒绝写请求（FR-CV-VER-010）。</p>
 */
@RestController
@RequestMapping("/api/v1")
class TimeMachineController {

    private final ContentTimeMachineService timeMachine;

    TimeMachineController(ContentTimeMachineService timeMachine) {
        this.timeMachine = timeMachine;
    }

    /**
     * @param at 历史时间点。要求绝对时刻（ISO-8601，推荐带时区，如 {@code 2026-09-19T05:00:00Z}）；
     *           缺省时取服务器当前时刻，等价于「看一眼现在的站点」。无时区的本地时间串会被拒绝，
     *           避免服务端按自己的时区猜测、把用户选的时间点算偏。
     */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/time-machine")
    ContentTimeMachineService.Snapshot at(@PathVariable UUID siteId,
                                          @RequestParam(required = false) String at) {
        return timeMachine.at(siteId, parseInstant(at));
    }

    static Instant parseInstant(String at) {
        if (at == null || at.isBlank()) return null;
        String value = at.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 允许带偏移量的写法（+08:00），但拒绝「没有时区」的裸本地时间
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "时间点格式不正确：需要带时区的 ISO-8601 时刻（如 2026-09-19T05:00:00Z）");
        }
    }
}
