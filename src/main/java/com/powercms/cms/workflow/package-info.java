@org.springframework.modulith.ApplicationModule(
    displayName = "Workflow",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = { "audit", "content", "outbox" }
)
package com.powercms.cms.workflow;
