package com.powercms.cms.search;

import com.powercms.cms.search.entity.SearchIndexMappingStateEntity;
import com.powercms.cms.search.repo.SearchIndexMappingStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * FR-MODEL-006：映射状态的落库出口必须自成事务。
 *
 * <p>这里断言的是**注解本身**，因为注解就是契约：{@code AdminSearchService.indexAll} 处在
 * {@code @Transactional(readOnly = true)} 里，只读事务的 Hibernate flush 模式是 {@code MANUAL}，
 * 在没有独立事务时 {@code repo.save} 会静默丢弃写入（无异常、无日志）。若把
 * {@code REQUIRES_NEW} 当作「多余注解」删掉，这个缺陷会以完全无声的方式回归 ——
 * 单元测试层没有别的断点能发现它，故在此显式钉住。
 */
class SearchIndexMappingStateWriterTest {

    @Test
    void upsertRunsInItsOwnTransaction() throws Exception {
        Transactional annotation = SearchIndexMappingStateWriter.class
            .getMethod("upsert", SearchIndexMappingStateEntity.class)
            .getAnnotation(Transactional.class);

        assertThat(annotation).as("upsert 必须有 @Transactional（否则只读调用方会静默丢弃写入）").isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void upsertDelegatesToRepository() {
        SearchIndexMappingStateRepository repo = mock(SearchIndexMappingStateRepository.class);
        SearchIndexMappingStateWriter writer = new SearchIndexMappingStateWriter(repo);
        SearchIndexMappingStateEntity entity = new SearchIndexMappingStateEntity();
        entity.setSiteId(UUID.randomUUID());
        entity.setTarget(SearchIndexMappingStateEntity.TARGET_ADMIN);

        writer.upsert(entity);

        verify(repo).save(entity);
    }

    /** 落库失败（约束冲突/连接断开）不得冒泡：基线记录是旁路能力，不能带崩重建/发布。 */
    @Test
    void upsertFailureDoesNotPropagate() {
        SearchIndexMappingStateRepository repo = mock(SearchIndexMappingStateRepository.class);
        doThrow(new IllegalStateException("db down")).when(repo).save(any(SearchIndexMappingStateEntity.class));
        SearchIndexMappingStateWriter writer = new SearchIndexMappingStateWriter(repo);
        SearchIndexMappingStateEntity entity = new SearchIndexMappingStateEntity();
        entity.setTarget(SearchIndexMappingStateEntity.TARGET_ADMIN);

        writer.upsert(entity);
        writer.upsert(null);
    }
}
