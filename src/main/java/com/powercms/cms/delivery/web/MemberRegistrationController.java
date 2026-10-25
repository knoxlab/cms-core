package com.powercms.cms.delivery.web;

import com.powercms.cms.identity.MemberRegistrationService;
import com.powercms.cms.identity.MemberRegistrationService.RegistrationForm;
import com.powercms.cms.identity.MemberRegistrationService.RegistrationResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 公开的会员注册：读取已发布会员资料字段，并按站点策略直接通过或进入审核。
 */
@RestController
@RequestMapping("/api/v1/delivery/sites/{siteId}/member-registration")
class MemberRegistrationController {

    private final MemberRegistrationService registration;

    MemberRegistrationController(MemberRegistrationService registration) {
        this.registration = registration;
    }

    public record RegisterBody(String username, String password, Map<String, Object> fields) {}

    @GetMapping
    RegistrationForm form(@PathVariable UUID siteId) {
        return registration.form(siteId);
    }

    @PostMapping
    RegistrationResult register(@PathVariable UUID siteId, @RequestBody RegisterBody body) {
        RegisterBody req = body == null ? new RegisterBody(null, null, Map.of()) : body;
        return registration.register(siteId, req.username(), req.password(), req.fields());
    }
}
