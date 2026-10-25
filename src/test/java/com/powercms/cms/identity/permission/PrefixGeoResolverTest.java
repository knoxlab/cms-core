package com.powercms.cms.identity.permission;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PrefixGeoResolverTest {

    @Test
    void gzipStreamUsesTheSameRowsAsPlainCsv() throws Exception {
        byte[] gzip;
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             GZIPOutputStream zip = new GZIPOutputStream(raw)) {
            zip.write("192.0.2.0/24,CN,GD\n".getBytes(StandardCharsets.UTF_8));
            zip.finish();
            gzip = raw.toByteArray();
        }
        PrefixGeoResolver geo = PrefixGeoResolver.load(new ByteArrayInputStream(gzip));
        assertThat(geo.size()).isEqualTo(1);
        assertThat(geo.locate("192.0.2.10")).contains(new GeoResolver.Place("CN", "GD"));
        assertThat(geo.locate("198.51.100.4")).isEmpty();
    }

    @Test
    void largeFileUsesBinarySearch() throws Exception {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < PrefixGeoResolver.INDEX_THRESHOLD + 100; i++) {
            csv.append("10.").append(i / 65536).append('.').append((i / 256) % 256).append('.')
                .append(i % 256).append("/32,CN,GD\n");
        }
        csv.append("203.0.113.0/24,US,\n");
        PrefixGeoResolver geo = PrefixGeoResolver.load(new java.io.StringReader(csv.toString()));
        assertThat(geo.size()).isGreaterThan(PrefixGeoResolver.INDEX_THRESHOLD);
        assertThat(geo.locate("10.0.1.2")).contains(new GeoResolver.Place("CN", "GD"));
        assertThat(geo.locate("203.0.113.9")).contains(new GeoResolver.Place("US", ""));
        assertThat(geo.locate("198.51.100.4")).isEmpty();
    }

    @Test
    void shippedNationalDatabaseResolvesProvincesAndCountries() throws Exception {
        try (var in = PrefixGeoResolverTest.class.getClassLoader().getResourceAsStream("geo/cn-ip.csv.gz")) {
            assertThat(in).isNotNull();
            PrefixGeoResolver geo = PrefixGeoResolver.load(in);
            assertThat(geo.size()).isGreaterThan(100_000);
            assertThat(geo.locate("114.114.114.114")).contains(new GeoResolver.Place("CN", "JS"));
            assertThat(geo.locate("202.96.134.133")).contains(new GeoResolver.Place("CN", "GD"));
            assertThat(geo.locate("223.5.5.5")).contains(new GeoResolver.Place("CN", "ZJ"));
            assertThat(geo.locate("8.8.8.8")).contains(new GeoResolver.Place("US", ""));
            assertThat(geo.locate("10.0.0.1")).isEmpty();
        }
    }
}
