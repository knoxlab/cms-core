package com.powercms.cms.sitepackage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMS 版本范围匹配（FR-PACKAGE-002「版本不兼容时阻断」）。
 * 该判定是导入门禁的一部分，必须可预测：宁可拒绝边界含糊的表达式，也不能误放行。
 */
class SemverRangeTest {

    @Test
    void admitsCurrentVersionLine() {
        assertThat(SemverRange.parse(">=12.0.0").admits("12.0.0")).isTrue();
        assertThat(SemverRange.parse(">=12.0.0").admits("12.0.0-SNAPSHOT")).isTrue();
        assertThat(SemverRange.parse(">=12.0.0").admits("12.4.1")).isTrue();
        assertThat(SemverRange.parse(">=12.0.0").admits("11.9.9")).isFalse();
    }

    @Test
    void rejectsIncompatibleRange() {
        assertThat(SemverRange.parse(">=99.0.0 <100.0.0").admits("12.0.0")).isFalse();
        assertThat(SemverRange.parse(">=11.0.0 <12.0.0").admits("12.0.0")).isFalse();
        assertThat(SemverRange.parse(">=11.0.0 <13.0.0").admits("12.0.0")).isTrue();
    }

    @Test
    void supportsCaretTildeAndWildcard() {
        assertThat(SemverRange.parse("^12.3.0").admits("12.9.0")).isTrue();
        assertThat(SemverRange.parse("^12.3.0").admits("13.0.0")).isFalse();
        assertThat(SemverRange.parse("~12.3.0").admits("12.3.9")).isTrue();
        assertThat(SemverRange.parse("~12.3.0").admits("12.4.0")).isFalse();
        assertThat(SemverRange.parse("12.x").admits("12.9.9")).isTrue();
        assertThat(SemverRange.parse("12.x").admits("13.0.0")).isFalse();
        assertThat(SemverRange.parse("*").admits("1.0.0")).isTrue();
    }

    @Test
    void supportsAlternativesAndRejectsGarbage() {
        assertThat(SemverRange.parse(">=12.0.0 || <10.0.0").admits("9.5.0")).isTrue();
        assertThat(SemverRange.parse(">=12.0.0 || <10.0.0").admits("11.0.0")).isFalse();
        assertThat(SemverRange.parse("latest-version")).isNull();
        assertThat(SemverRange.parse(">=abc")).isNull();
        assertThat(SemverRange.parse("")).isNull();
    }
}
