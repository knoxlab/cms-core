package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failed-login counting and lockout (FR-IAM-002). Does not log passwords.
 */
@Component
class IdentityAuthenticationEvents {

    private static final Logger log = LoggerFactory.getLogger(IdentityAuthenticationEvents.class);

    private final UserRepository users;
    private final IdentityProperties properties;
    private final AuditService audit;

    IdentityAuthenticationEvents(UserRepository users, IdentityProperties properties, AuditService audit) {
        this.users = users;
        this.properties = properties;
        this.audit = audit;
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication() == null ? null : event.getAuthentication().getName();
        if (username == null || username.isBlank()) return;
        findUser(username).ifPresent(user -> {
            if (user.getFailedLogins() != 0) {
                user.setFailedLogins(0);
                users.save(user);
            }
        });
    }

    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        if (!(event instanceof AuthenticationFailureBadCredentialsEvent)) {
            delay();
            return;
        }
        String username = event.getAuthentication() == null ? null : event.getAuthentication().getName();
        if (username != null && !username.isBlank()) {
            findUser(username).ifPresent(this::recordFailure);
        }
        delay();
    }

    /**
     * 按登录标识解析（USERNAME 模式为用户名，EMAIL 模式为邮箱），与
     * {@code IdentityUserDetailsService} 认证链同口径，保证邮件模式下失败计数/清零同样生效。
     */
    private java.util.Optional<UserEntity> findUser(String identifier) {
        return users.findByUsername(identifier)
            .or(() -> users.findByEmailIgnoreCase(identifier));
    }

    private void recordFailure(UserEntity user) {
        if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) return;
        int next = user.getFailedLogins() + 1;
        user.setFailedLogins(next);
        int threshold = Math.max(1, properties.getMaxFailedLogins());
        if (next >= threshold) {
            user.setStatus(UserEntity.STATUS_LOCKED);
            audit.write(user.getUsername(), "USER_LOCKED", "User", user.getId().toString(),
                null, "SUCCESS", "failedLogins=" + next);
            log.info("用户已锁定 username={} failedLogins={}", user.getUsername(), next);
        } else {
            audit.write(user.getUsername(), "LOGIN_FAILURE", "User", user.getId().toString(),
                null, "FAILURE", "failedLogins=" + next);
        }
        users.save(user);
    }

    private void delay() {
        int ms = properties.getFailureDelayMs();
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
