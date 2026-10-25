package com.powercms.cms.workflow.web;

import com.powercms.cms.workflow.NotificationService;
import com.powercms.cms.workflow.NotificationTemplateService;
import com.powercms.cms.workflow.WorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class WorkflowController {

    private final WorkflowService service;
    private final NotificationService notifications;
    private final NotificationTemplateService templates;

    WorkflowController(WorkflowService service, NotificationService notifications,
                       NotificationTemplateService templates) {
        this.service = service;
        this.notifications = notifications;
        this.templates = templates;
    }

    // ---------- Definition ----------

    record CreateWfReq(@NotBlank String name, String description,
                       List<WorkflowService.TransInput> transitions) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @PostMapping("/sites/{siteId}/workflows")
    ResponseEntity<WorkflowService.WfDefInfo> create(@PathVariable UUID siteId,
                                                       @Valid @RequestBody CreateWfReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createDefinition(siteId, req.name(), req.description(), req.transitions()));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/workflows")
    List<WorkflowService.WfDefInfo> list(@PathVariable UUID siteId) { return service.listDefinitions(siteId); }

    @PreAuthorize("@siteAuthorization.workflow(authentication, #id, 'read')")
    @GetMapping("/workflows/{id}")
    WorkflowService.WfDefInfo get(@PathVariable UUID id) { return service.getDefinition(id); }

    @PreAuthorize("@siteAuthorization.workflow(authentication, #id, 'admin')")
    @PostMapping("/workflows/{id}/publish")
    ResponseEntity<Void> publish(@PathVariable UUID id) { service.publish(id); return ResponseEntity.noContent().build(); }

    @PreAuthorize("@siteAuthorization.workflow(authentication, #id, 'admin')")
    @PostMapping("/workflows/{id}/deprecate")
    ResponseEntity<Void> deprecate(@PathVariable UUID id) { service.deprecate(id); return ResponseEntity.noContent().build(); }

    record UpdateWfReq(String name, String description, Integer timeoutMinutes,
                       Integer reminderIntervalMinutes, String timeoutActionKey,
                       List<WorkflowService.TransInput> transitions) {}

    @PreAuthorize("@siteAuthorization.workflow(authentication, #id, 'admin')")
    @PutMapping("/workflows/{id}")
    WorkflowService.WfDefInfo update(@PathVariable UUID id, @RequestBody UpdateWfReq req) {
        return service.updateDraft(id, req.name(), req.description(), req.timeoutMinutes(),
            req.reminderIntervalMinutes(), req.timeoutActionKey(), req.transitions());
    }

    @PreAuthorize("@siteAuthorization.workflow(authentication, #id, 'admin')")
    @PostMapping("/workflows/{id}/new-version")
    ResponseEntity<WorkflowService.WfDefInfo> newVersion(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.newVersion(id));
    }

    // ---------- Task ----------

    record SubmitReq(@NotNull UUID workflowId, @NotBlank String targetStatus, @NotBlank String assignee,
                     Instant scheduledAt) {}

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'write') and @siteAuthorization.workflow(authentication, #req.workflowId(), 'read')")
    @PostMapping("/contents/{id}/submit")
    ResponseEntity<WorkflowService.TaskInfo> submit(@PathVariable UUID id,
                                                      @Valid @RequestBody SubmitReq req,
                                                      Authentication auth) {
        return ResponseEntity.ok(service.submitForReview(req.workflowId(), id, req.targetStatus(),
            req.assignee(), auth.getName(), req.scheduledAt()));
    }

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'read')")
    @GetMapping("/contents/{id}/available-actions")
    List<WorkflowService.ActionInfo> availableActions(@PathVariable UUID id) {
        return service.availableActions(id);
    }

    record ResolveReq(String comment) {}

    @PreAuthorize("@siteAuthorization.task(authentication, #id, 'review')")
    @PostMapping("/tasks/{id}/approve")
    ResponseEntity<WorkflowService.TaskInfo> approve(@PathVariable UUID id,
                                                       @RequestBody(required = false) ResolveReq req,
                                                       Authentication auth) {
        return ResponseEntity.ok(service.approve(id, req == null ? null : req.comment(), auth.getName()));
    }

    @PreAuthorize("@siteAuthorization.task(authentication, #id, 'review')")
    @PostMapping("/tasks/{id}/reject")
    ResponseEntity<WorkflowService.TaskInfo> reject(@PathVariable UUID id,
                                                      @RequestBody(required = false) ResolveReq req,
                                                      Authentication auth) {
        return ResponseEntity.ok(service.reject(id, req == null ? null : req.comment(), auth.getName()));
    }

    @PreAuthorize("@siteAuthorization.task(authentication, #id, 'write') or @siteAuthorization.task(authentication, #id, 'admin')")
    @PostMapping("/tasks/{id}/cancel")
    ResponseEntity<WorkflowService.TaskInfo> cancel(@PathVariable UUID id,
                                                      @RequestBody(required = false) ResolveReq req,
                                                      Authentication auth) {
        return ResponseEntity.ok(service.cancel(id, req == null ? null : req.comment(), auth.getName()));
    }

    record DelegateReq(@NotBlank String newAssignee) {}

    @PreAuthorize("@siteAuthorization.task(authentication, #id, 'review')")
    @PostMapping("/tasks/{id}/delegate")
    ResponseEntity<WorkflowService.TaskInfo> delegate(@PathVariable UUID id,
                                                        @Valid @RequestBody DelegateReq req,
                                                        Authentication auth) {
        return ResponseEntity.ok(service.delegate(id, req.newAssignee(), auth.getName()));
    }

    record RescheduleReq(@NotNull Instant scheduledAt) {}

    @PreAuthorize("@siteAuthorization.task(authentication, #id, 'review')")
    @PostMapping("/tasks/{id}/reschedule")
    ResponseEntity<WorkflowService.TaskInfo> reschedule(@PathVariable UUID id,
                                                          @Valid @RequestBody RescheduleReq req,
                                                          Authentication auth) {
        return ResponseEntity.ok(service.reschedule(id, req.scheduledAt(), auth.getName()));
    }

    record ScheduleWithdrawReq(Instant scheduledWithdrawAt) {}

    @PreAuthorize("@siteAuthorization.content(authentication, #id, 'review')")
    @PostMapping("/contents/{id}/schedule-withdraw")
    ResponseEntity<java.util.Map<String, Instant>> scheduleWithdraw(@PathVariable UUID id,
                                                                      @RequestBody(required = false) ScheduleWithdrawReq req,
                                                                      Authentication auth) {
        Instant when = service.scheduleWithdraw(id, req == null ? null : req.scheduledWithdrawAt(), auth.getName());
        java.util.Map<String, Instant> body = new java.util.LinkedHashMap<>();
        body.put("scheduledWithdrawAt", when);
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("@siteAuthorization.task(authentication, #id, 'read')")
    @GetMapping("/tasks/{id}/timeline")
    List<WorkflowService.TimelineEntry> timeline(@PathVariable UUID id) {
        return service.timeline(id);
    }

    /**
     * 待办列表（系统 → 工作流 → 待办）。
     *
     * <p>身份来自登录态（{@code auth.getName()}），{@code siteId} 只用来限定站点范围，
     * 不能用来查询他人的待办。</p>
     */
    @PreAuthorize("isAuthenticated() and (#siteId == null or @siteAuthorization.check(authentication, #siteId, 'read'))")
    @GetMapping("/tasks/pending")
    List<WorkflowService.TaskInfo> pending(@RequestParam(name = "siteId", required = false) UUID siteId,
                                          Authentication auth) {
        return service.pendingTasks(auth.getName(), siteId);
    }

    // ---------- Notification ----------

    @PreAuthorize("isAuthenticated()")
    @GetMapping({"/notifications", "/me/notifications"})
    List<NotificationService.NotifInfo> notifications(Authentication auth,
                                                      @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
        return service.notificationsDetailed(auth.getName(), unreadOnly);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/notifications/unread-count")
    java.util.Map<String, Long> unreadCount(Authentication auth) {
        return java.util.Map.of("unreadCount", service.unreadCount(auth.getName()));
    }

    @PreAuthorize("@siteAuthorization.notification(authentication, #id)")
    @PostMapping("/notifications/{id}/read")
    ResponseEntity<Void> markRead(@PathVariable UUID id) { service.markRead(id); return ResponseEntity.noContent().build(); }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/notifications/read-all")
    java.util.Map<String, Integer> markAllRead(Authentication auth) {
        return java.util.Map.of("updated", service.markAllRead(auth.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me/notification-preferences")
    List<NotificationService.PrefInfo> preferences(Authentication auth) {
        return notifications.preferences(auth.getName());
    }

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/me/notification-preferences")
    List<NotificationService.PrefInfo> savePreferences(Authentication auth,
                                                       @RequestBody List<NotificationService.PrefInfo> items) {
        return notifications.savePreferences(auth.getName(), items);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/notification-templates")
    List<NotificationTemplateService.TemplateInfo> listTemplates(@PathVariable UUID siteId) {
        return templates.list(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/notification-templates/{key}")
    NotificationTemplateService.TemplateInfo getTemplate(@PathVariable UUID siteId, @PathVariable String key) {
        return templates.get(siteId, key);
    }

    record TemplateUpsertReq(String subject, String bodyHtml, String bodyText) {}

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @PutMapping("/sites/{siteId}/notification-templates/{key}")
    NotificationTemplateService.TemplateInfo upsertTemplate(@PathVariable UUID siteId, @PathVariable String key,
                                                            @RequestBody TemplateUpsertReq req, Authentication auth) {
        return templates.upsert(siteId, key, req.subject(), req.bodyHtml(), req.bodyText(), auth.getName());
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @PostMapping("/sites/{siteId}/notification-templates/{key}/preview")
    NotificationTemplateService.Rendered previewTemplate(@PathVariable UUID siteId, @PathVariable String key,
                                                         @RequestBody(required = false) java.util.Map<String, Object> sample) {
        return templates.preview(siteId, key, sample);
    }
}
