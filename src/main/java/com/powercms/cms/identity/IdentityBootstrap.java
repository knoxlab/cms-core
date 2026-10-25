package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.RoleEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class IdentityBootstrap {

    private static final Logger log = LoggerFactory.getLogger(IdentityBootstrap.class);
    private final IdentityServiceImpl impl;
    private final IdentityProperties properties;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    IdentityBootstrap(IdentityServiceImpl impl, IdentityProperties properties,
                      UserRepository users, PasswordEncoder encoder) {
        this.impl = impl;
        this.properties = properties;
        this.users = users;
        this.encoder = encoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        UUID orgId = impl.ensureOrganization("默认组织");
        log.info("组织就绪 id={}", orgId);

        impl.ensureBackendLayouts();
        log.info("后台布局就绪");

        RoleEntity adminRole = impl.bootstrapRole(orgId,
            IdentityServiceImpl.ROLE_SYS_ADMIN, "系统管理员", "全部管理权限");
        log.info("角色就绪 code={}", adminRole.getCode());

        if (impl.listUsers(orgId).isEmpty()) {
            impl.bootstrapAdmin(orgId, "admin", properties.getFactoryPassword(), adminRole.getId());
            log.info("默认管理员已创建 username=admin");
        }

        users.findByUsername("admin").ifPresent(this::checkFactoryPassword);
    }

    private void checkFactoryPassword(UserEntity user) {
        String factory = properties.getFactoryPassword();
        if (factory == null || factory.isBlank() || user.getPasswordHash() == null) return;
        if (!encoder.matches(factory, user.getPasswordHash())) return;
        if (properties.isRejectFactoryPassword()) {
            throw new IllegalStateException("生产环境禁止使用出厂管理员密码，请修改 admin 密码后启动");
        }
        log.warn("管理员仍使用出厂密码，生产环境应设置 powercms.identity.reject-factory-password=true");
    }
}
