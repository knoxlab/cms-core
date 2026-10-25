package com.powercms.cms;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 模块边界架构测试（NFR-MAINT-001）。
 * 验证所有跨模块依赖已在 @ApplicationModule(allowedDependencies) 中声明。
 */
class ModularityTests {

    @Test
    void verifyModuleBoundaries() {
        ApplicationModules.of(CmsApplication.class).verify();
    }
}
