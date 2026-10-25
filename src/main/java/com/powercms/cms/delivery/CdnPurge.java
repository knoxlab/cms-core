package com.powercms.cms.delivery;

import java.util.List;
import java.util.UUID;

/** CDN 清理 SPI。缺省实现只记日志，不接具体厂商。 */
public interface CdnPurge {

    void purge(UUID siteId, List<String> paths);
}
