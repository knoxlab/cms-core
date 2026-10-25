package com.powercms.cms.platform.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyBundleCoverageTest {

    private static final List<String> FILES = List.of(
        "i18n/legacy.properties",
        "i18n/legacy_en_US.properties",
        "i18n/legacy_ja_JP.properties",
        "i18n/legacy_ko_KR.properties",
        "i18n/legacy_fr_FR.properties",
        "i18n/legacy_es_ES.properties");

    @Test
    void eachLegacyBundleHas733NonEmptyEntries() throws Exception {
        for (String path : FILES) {
            int count = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    assertThat(eq).as(path + " malformed: " + line).isGreaterThan(0);
                    String value = line.substring(eq + 1);
                    assertThat(value).as(path + " missing value: " + line).isNotNull();
                    count++;
                }
            }
            assertThat(count).as(path).isEqualTo(733);
        }
    }

    @Test
    void runtimeMessageSourceResolvesLegacyKeyInAllLocales() {
        var source = new I18nConfig().messageSource();
        String key = Messages.keyOf("用户名或密码错误");
        assertThat(source.getMessage(key, null, Locale.forLanguageTag("zh-CN"))).isEqualTo("用户名或密码错误");
        assertThat(source.getMessage(key, null, Locale.forLanguageTag("en-US")))
            .isEqualTo("Incorrect username or password");
        assertThat(source.getMessage(key, null, Locale.forLanguageTag("ja-JP"))).isNotBlank()
            .isNotEqualTo("用户名或密码错误");
        assertThat(source.getMessage(key, null, Locale.forLanguageTag("ko-KR"))).isNotBlank()
            .isNotEqualTo("用户名或密码错误");
        assertThat(source.getMessage(key, null, Locale.forLanguageTag("fr-FR"))).isNotBlank()
            .isNotEqualTo("用户名或密码错误");
        assertThat(source.getMessage(key, null, Locale.forLanguageTag("es-ES"))).isNotBlank()
            .isNotEqualTo("用户名或密码错误");
    }
}
