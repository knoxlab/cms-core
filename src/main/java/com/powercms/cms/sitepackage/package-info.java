@org.springframework.modulith.ApplicationModule(
    displayName = "Site Packages",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {
        "audit", "content", "delivery", "model", "site", "widget", "publish"
    , "platform"}
)
package com.powercms.cms.sitepackage;
