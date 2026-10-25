package com.powercms.cms.platform.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SupportedLocalesTest {

    @Test
    void whitelistIsExactlyTheSixRequiredLanguages() {
        assertThat(SupportedLocales.SUPPORTED_TAGS).containsExactly(
            "zh-CN", "en-US", "ja-JP", "ko-KR", "fr-FR", "es-ES");
        assertThat(SupportedLocales.DEFAULT_TAG).isEqualTo("zh-CN");
    }

    @Test
    void normalizeAcceptsAliasesAndIgnoresCaseAndUnderscore() {
        assertThat(SupportedLocales.normalize("en")).isEqualTo("en-US");
        assertThat(SupportedLocales.normalize("EN_US")).isEqualTo("en-US");
        assertThat(SupportedLocales.normalize("zh")).isEqualTo("zh-CN");
        assertThat(SupportedLocales.normalize("zh-Hans")).isEqualTo("zh-CN");
        assertThat(SupportedLocales.normalize("es-419")).isEqualTo("es-ES");
        assertThat(SupportedLocales.normalize("fr-CA")).isEqualTo("fr-FR");
        assertThat(SupportedLocales.normalize(" ja-jp ")).isEqualTo("ja-JP");
        assertThat(SupportedLocales.normalize("ko")).isEqualTo("ko-KR");
    }

    @Test
    void unsupportedInputReturnsNull() {
        assertThat(SupportedLocales.normalize(null)).isNull();
        assertThat(SupportedLocales.normalize("")).isNull();
        assertThat(SupportedLocales.normalize("de-DE")).isNull();
        assertThat(SupportedLocales.isSupported("pt-BR")).isFalse();
        assertThat(SupportedLocales.parse("de")).isNull();
    }

    @Test
    void parseOrDefaultFallsBackToZhCn() {
        assertThat(SupportedLocales.tag(SupportedLocales.parseOrDefault("nope")))
            .isEqualTo("zh-CN");
        Locale ja = SupportedLocales.parseOrDefault("ja");
        assertThat(SupportedLocales.tag(ja)).isEqualTo("ja-JP");
    }
}
