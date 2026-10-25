package com.powercms.cms.identity.permission;

import java.util.Optional;

/** 缺省实现：没有离线库时无法定位，策略里一旦限制地域就拒绝。 */
public class UnavailableGeoResolver implements GeoResolver {

    @Override
    public Optional<Place> locate(String clientIp) {
        return Optional.empty();
    }
}
