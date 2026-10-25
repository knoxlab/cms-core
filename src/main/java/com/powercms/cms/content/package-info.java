@org.springframework.modulith.ApplicationModule(
    displayName = "Categories & Content",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {
        "model", "identity", "outbox", "media",
        "platform", "search", "audit", "delivery", "sitefolder", "sitepackage"
    }
)
package com.powercms.cms.content;
