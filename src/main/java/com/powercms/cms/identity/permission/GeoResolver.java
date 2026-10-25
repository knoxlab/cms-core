package com.powercms.cms.identity.permission;

import java.util.Optional;

/**
 * 离线地域库 SPI（D6）。未接入或解析失败时返回 empty，调用方按拒绝处理。
 */
public interface GeoResolver {

    record Place(String country, String province) {}

    Optional<Place> locate(String clientIp);
}
