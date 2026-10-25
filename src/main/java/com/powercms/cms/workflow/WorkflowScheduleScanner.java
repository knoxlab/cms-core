package com.powercms.cms.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "powercms.workflow.schedule-scan-enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowScheduleScanner {

    private final WorkflowScheduleService schedules;

    WorkflowScheduleScanner(WorkflowScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(fixedDelayString = "${powercms.workflow.schedule-scan-ms:60000}")
    public void scan() {
        schedules.processDue(Instant.now());
    }
}
