package com.powercms.cms.workflow;

import com.powercms.cms.workflow.entity.NotificationTemplateEntity;
import com.powercms.cms.workflow.repo.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    @Mock NotificationTemplateRepository repo;
    NotificationTemplateService service;

    @BeforeEach
    void setUp() {
        service = new NotificationTemplateService(repo);
    }

    @Test
    void previewRendersFreemarkerFromGlobalTemplate() {
        var row = new NotificationTemplateEntity();
        row.setId(UUID.randomUUID());
        row.setTemplateKey("task_submitted");
        row.setSubject("审核：${contentTitle}");
        row.setBodyHtml("<p>${submitter} 提交了《${contentTitle}》</p>");
        row.setBodyText("${contentTitle}");
        row.setUpdatedAt(Instant.now());
        when(repo.findBySiteIdIsNullAndTemplateKey("task_submitted")).thenReturn(Optional.of(row));

        var rendered = service.preview(null, "task_submitted", Map.of(
            "contentTitle", "首页", "submitter", "editor"));

        assertThat(rendered.subject()).isEqualTo("审核：首页");
        assertThat(rendered.html()).contains("editor").contains("首页");
        assertThat(rendered.text()).isEqualTo("首页");
    }

    @Test
    void renderFallsBackWhenTemplateMissing() {
        when(repo.findBySiteIdAndTemplateKey(any(), any())).thenReturn(Optional.empty());
        when(repo.findBySiteIdIsNullAndTemplateKey(any())).thenReturn(Optional.empty());

        var rendered = service.render(UUID.randomUUID(), "task_submitted", Map.of(), "fb-subject", "fb-body");
        assertThat(rendered.subject()).isEqualTo("fb-subject");
        assertThat(rendered.html()).isEqualTo("fb-body");
    }
}
