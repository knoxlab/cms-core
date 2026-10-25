@org.springframework.modulith.ApplicationModule(
    displayName = "Build & Publish",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {
        "audit", "content", "delivery", "identity", "media", "model", "platform", "sitepackage"
    }
)
package com.powercms.cms.publish;
