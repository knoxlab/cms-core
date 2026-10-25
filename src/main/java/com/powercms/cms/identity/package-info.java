@org.springframework.modulith.ApplicationModule(
    displayName = "Identity & Access",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {
        "audit", "content", "media", "model",
        "platform", "search", "site", "sitefolder", "stats", "workflow"
    }
)
package com.powercms.cms.identity;