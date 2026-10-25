package com.powercms.cms.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.identity.ApiTokenService;
import com.powercms.cms.identity.OAuthTokenService;
import com.powercms.cms.identity.UserLanguageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IdentityAuthConfiguration {

    @Bean
    ApiTokenAuthenticationFilter apiTokenAuthenticationFilter(ApiTokenService tokens,
                                                             ObjectProvider<OAuthTokenService> oauthTokens,
                                                             ObjectMapper objectMapper,
                                                             org.springframework.context.MessageSource messageSource,
                                                             UserLanguageService languages) {
        return new ApiTokenAuthenticationFilter(tokens, oauthTokens, objectMapper, messageSource, languages);
    }

    /** Keep the Bearer filter inside Spring Security only; do not register it as a servlet filter. */
    @Bean
    FilterRegistrationBean<ApiTokenAuthenticationFilter> disableApiTokenFilterRegistration(
        ApiTokenAuthenticationFilter filter) {
        FilterRegistrationBean<ApiTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
