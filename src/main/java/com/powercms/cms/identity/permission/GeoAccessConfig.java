package com.powercms.cms.identity.permission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 配置了 {@code powercms.access.geo-db} 时用离线前缀库替换缺省拒绝实现。 */
@Configuration
public class GeoAccessConfig {

    @Bean
    @ConditionalOnProperty(name = "powercms.access.geo-db")
    public GeoResolver prefixGeoResolver(@Value("${powercms.access.geo-db}") String location) throws IOException {
        if (location.startsWith("classpath:")) {
            String name = location.substring("classpath:".length());
            InputStream in = GeoAccessConfig.class.getClassLoader().getResourceAsStream(name);
            if (in == null) {
                throw new IOException("geo db not found: " + location);
            }
            PrefixGeoResolver resolver = PrefixGeoResolver.load(in);
            org.slf4j.LoggerFactory.getLogger(GeoAccessConfig.class)
                .info("offline geo db loaded from {} rows={}", location, resolver.size());
            return resolver;
        }
        Path file = Path.of(location);
        if (!Files.isRegularFile(file)) {
            throw new IOException("geo db not found: " + location);
        }
        return PrefixGeoResolver.load(file);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(GeoResolver.class)
    public GeoResolver unavailableGeoResolver() {
        return new UnavailableGeoResolver();
    }
}
