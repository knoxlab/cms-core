package com.powercms.cms.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class LoggingCdnPurge implements CdnPurge {

    private static final Logger log = LoggerFactory.getLogger(LoggingCdnPurge.class);

    @Override
    public void purge(UUID siteId, List<String> paths) {
        log.warn("CDN purge requested site={} paths={} (no CDN adapter configured)", siteId, paths);
    }
}
