@org.springframework.modulith.ApplicationModule(
    displayName = "Content Delivery API",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {
        "audit", "content", "identity", "model",
        "publish", "site", "sitefolder", "sitepackage", "widget"
    }
)
package com.powercms.cms.delivery;