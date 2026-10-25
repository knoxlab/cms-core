package com.powercms.cms.platform.web;

import com.powercms.cms.identity.IdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class OrganizationContextFilter extends HttpFilter {

    private static final Logger log = LoggerFactory.getLogger(OrganizationContextFilter.class);
    private final IdentityService identityService;

    public OrganizationContextFilter(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        try {
            UUID orgId = identityService.ensureOrganization("默认组织");
            request.setAttribute("organizationId", orgId);
        } catch (Exception e) {
            log.debug("Organization not yet available, continuing without");
        }
        chain.doFilter(request, response);
    }
}
