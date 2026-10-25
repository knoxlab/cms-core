package com.powercms.cms.identity.web;

import com.powercms.cms.identity.OrganizationSettingsService;
import com.powercms.cms.identity.dto.OrganizationSettingsResponse;
import com.powercms.cms.identity.dto.UpdateOrganizationSettingsRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 组织设置（spec 9.7）：读取需后台登录；修改需 system-admin。首期单组织。
 */
@RestController
@RequestMapping("/api/v1/organization/settings")
public class OrganizationSettingsController {

    private final OrganizationSettingsService settingsService;

    public OrganizationSettingsController(OrganizationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public OrganizationSettingsResponse get(@RequestAttribute("organizationId") UUID organizationId) {
        return settingsService.get(organizationId);
    }

    /** spec 5.5：登录页公开品牌（无需登录）。 */
    @GetMapping("/public/branding")
    public OrganizationSettingsResponse publicBranding() {
        return settingsService.publicBranding();
    }

    @PutMapping
    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    public OrganizationSettingsResponse update(@Valid @RequestBody UpdateOrganizationSettingsRequest req,
                                               @RequestAttribute("organizationId") UUID organizationId) {
        return settingsService.update(organizationId, req);
    }
}
