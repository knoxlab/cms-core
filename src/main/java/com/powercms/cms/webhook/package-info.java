@org.springframework.modulith.ApplicationModule(
    displayName = "Webhooks",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = { "outbox", "platform" }
)
package com.powercms.cms.webhook;
