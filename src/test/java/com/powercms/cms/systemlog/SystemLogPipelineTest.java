package com.powercms.cms.systemlog;

import com.powercms.cms.systemlog.entity.AccessLogEntity;
import com.powercms.cms.systemlog.entity.RuntimeLogEntity;
import com.powercms.cms.systemlog.repo.AccessLogRepository;
import com.powercms.cms.systemlog.repo.RuntimeLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-LOG-ST-002 管道行为：入队批量落库、队列满丢弃低级别、DB 失败不影响另一 sink。
 */
class SystemLogPipelineTest {

    private static RuntimeLogEntity runtime(String level) {
        return RuntimeLogEntity.create(Instant.now(), level, "svc", "mod", "node", "main",
            null, null, "msg-" + level, null, null, false, null);
    }

    private static AccessLogEntity access() {
        return AccessLogEntity.create(Instant.now(), "1.2.3.4", "GET", "/x", (short) 200, 5,
            null, "anonymous", null, null, null, "SUCCESS", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushesBothSinksIndependently() {
        RuntimeLogRepository runtimeRepo = mock(RuntimeLogRepository.class);
        AccessLogRepository accessRepo = mock(AccessLogRepository.class);
        SystemLogProperties props = new SystemLogProperties();
        props.setFlushInterval(java.time.Duration.ofMillis(50));
        SystemLogPipeline pipeline = new SystemLogPipeline(props, runtimeRepo, accessRepo);
        try {
            pipeline.submit(runtime("INFO"));
            pipeline.submit(access());
            verify(runtimeRepo, timeout(2000).atLeastOnce()).saveAll(anyList());
            verify(accessRepo, timeout(2000).atLeastOnce()).saveAll(anyList());
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void dbFailureOnOneSinkDoesNotBlockOther() {
        RuntimeLogRepository runtimeRepo = mock(RuntimeLogRepository.class);
        AccessLogRepository accessRepo = mock(AccessLogRepository.class);
        // runtime sink 故障（NFR-LOG-013：sink 间故障隔离）
        when(runtimeRepo.saveAll(anyList())).thenThrow(new RuntimeException("db down"));
        SystemLogProperties props = new SystemLogProperties();
        props.setFlushInterval(java.time.Duration.ofMillis(50));
        SystemLogPipeline pipeline = new SystemLogPipeline(props, runtimeRepo, accessRepo);
        try {
            pipeline.submit(runtime("ERROR"));
            pipeline.submit(access());
            // access sink 依然完成落库（AC-LOG-027）
            verify(accessRepo, timeout(2000).atLeastOnce()).saveAll(anyList());
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void overflowDropsDebugFirst() {
        RuntimeLogRepository runtimeRepo = mock(RuntimeLogRepository.class);
        AccessLogRepository accessRepo = mock(AccessLogRepository.class);
        SystemLogProperties props = new SystemLogProperties();
        props.setFlushInterval(java.time.Duration.ofHours(1)); // 不触发自动 flush
        props.setQueueCapacity(2);
        // startWriter=false：验证纯溢出策略，避免写者线程并发出队造成竞争
        SystemLogPipeline pipeline = new SystemLogPipeline(props, runtimeRepo, accessRepo, false);
        try {
            assertThat(pipeline.submit(runtime("DEBUG"))).isTrue();
            assertThat(pipeline.submit(runtime("TRACE"))).isTrue();
            // 队列已满：INFO 入队时应顶掉一条 DEBUG/TRACE，自身成功入队
            assertThat(pipeline.submit(runtime("INFO"))).isTrue();
            assertThat(pipeline.dropped.get()).isEqualTo(1);
            assertThat(pipeline.dropSummary()).contains("dropped=1");
            // 访问日志溢出：顶位入队
            assertThat(pipeline.submit(access())).isTrue();
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushedBatchContainsSubmittedEntities() {
        RuntimeLogRepository runtimeRepo = mock(RuntimeLogRepository.class);
        AccessLogRepository accessRepo = mock(AccessLogRepository.class);
        SystemLogProperties props = new SystemLogProperties();
        props.setFlushInterval(java.time.Duration.ofMillis(50));
        SystemLogPipeline pipeline = new SystemLogPipeline(props, runtimeRepo, accessRepo);
        try {
            RuntimeLogEntity e = runtime("WARN");
            pipeline.submit(e);
            ArgumentCaptor<List<RuntimeLogEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(runtimeRepo, timeout(2000).atLeastOnce()).saveAll(captor.capture());
            assertThat(captor.getValue()).contains(e);
        } finally {
            pipeline.shutdown();
        }
    }
}
