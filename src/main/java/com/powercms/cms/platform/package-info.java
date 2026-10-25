@org.springframework.modulith.ApplicationModule(
    displayName = "Platform",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {
        "identity", "audit", "site",
        "content", "model", "media",
        "workflow", "publish", "sitepackage",
        "delivery"
    }
)
package com.powercms.cms.platform;
