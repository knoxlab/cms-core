@org.springframework.modulith.ApplicationModule(
    displayName = "Content Models",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {
        "content", "site", "sitefolder", "sitepackage"
    }
)
package com.powercms.cms.model;
