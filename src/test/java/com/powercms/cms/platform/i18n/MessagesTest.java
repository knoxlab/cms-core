package com.powercms.cms.platform.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    private static final Locale EN = Locale.forLanguageTag("en-US");
    private static final Locale FR = Locale.forLanguageTag("fr-FR");

    private Messages messages;

    @BeforeEach
    void setUp() {
        MessageSource source = new I18nConfig().messageSource();
        messages = new Messages(source);
        LocaleContextHolder.resetLocaleContext();
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void splitSeparatesAsciiRunsAndFullWidthPunctuation() {
        assertThat(Messages.split("版本冲突，期望 3，实际 5")).containsExactly(
            "版本冲突", "，", "期望 ", "3", "，", "实际 ", "5");
        assertThat(Messages.split("路径必须以 /a 开头")).containsExactly(
            "路径必须以 ", "/", "a", " 开头");
    }

    @Test
    void keyOfPrefixesLegacy() {
        assertThat(Messages.keyOf("用户不存在")).isEqualTo("legacy.用户不存在");
    }

    @Test
    void byTextUsesWholeSentenceKeyWhenPresent() {
        assertThat(messages.byText("用户名或密码错误", EN)).isEqualTo("Incorrect username or password");
        assertThat(messages.byText("用户名或密码错误", FR))
            .isEqualTo("Nom d'utilisateur ou mot de passe incorrect");
    }

    @Test
    void byTextReassemblesSegmentedMessageWithLocalizedPunctuation() {
        String out = messages.byText("版本冲突，期望 3，实际 5", EN);
        assertThat(out).contains("Version conflict");
        assertThat(out).contains("3");
        assertThat(out).contains("5");
        assertThat(out).doesNotContain("，");
        assertThat(out).contains(",");
    }

    @Test
    void missingKeyFallsBackToSourceText() {
        assertThat(messages.byText("这段话不在目录里", EN)).isEqualTo("这段话不在目录里");
    }

    @Test
    void getUsesLocaleContextHolderAndFallsBackToCode() {
        LocaleContextHolder.setLocale(EN);
        assertThat(messages.get("error.auth.unauthenticated")).isEqualTo("Please sign in first");
        assertThat(messages.get("error.language.unsupported", "xx", "zh-CN"))
            .contains("xx")
            .contains("zh-CN");
        assertThat(messages.get("no.such.key")).isEqualTo("no.such.key");
    }

    @Test
    void getWithExplicitLocaleDoesNotDependOnHolder() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("zh-CN"));
        assertThat(messages.get("error.permission.denied", EN)).isEqualTo("Permission denied");
        assertThat(messages.get("error.permission.denied", FR)).isEqualTo("Permission refusée");
    }

    @Test
    void emptyAndNullPassThrough() {
        assertThat(messages.byText(null, EN)).isNull();
        assertThat(messages.byText("", EN)).isEmpty();
    }

    // ------------------------------------------------------------------ OBS-MS-04

    /**
     * 运行期拼装的文案没有整句键，只能逐段重组；而 .properties 解析会吞掉值的**首部空白**
     * （{@code key= value} 的空白被跳过），历史目录里的译文因此普遍缺首空格。
     * 空白必须一律取自源片段：这里用「键带首空格、值丢首空格」的目录形状复现缺陷，
     * 修复前会拼出 {@code "xendpoint"}。
     */
    @Test
    void segmentedReassemblyTakesWhitespaceFromSourcePieceNotFromCatalog() {
        var legacy = new StaticMessageSource();
        legacy.addMessage(Messages.keyOf("端点"), EN, "endpoint");
        assertThat(new Messages(legacy).byText("x 端点", EN))
            .as("归一形键（去首尾空白）").isEqualTo("x endpoint");

        // 历史形态：键保留了片段里的空白（.properties 用 \ 转义），值同样丢了首空格
        var historical = new StaticMessageSource();
        historical.addMessage(Messages.keyOf(" 端点"), EN, "endpoint");
        assertThat(new Messages(historical).byText("x 端点", EN))
            .as("带空白片段为键的历史条目").isEqualTo("x endpoint");
    }

    /** 中文基线必须能把拼装文案**原样往返**（修复前插值两侧的空格会被吞掉）。 */
    @Test
    void chineseBaselineRoundTripsAssembledMessagesVerbatim() {
        Locale zh = Locale.forLanguageTag("zh-CN");
        for (String text : List.of(
            "站点尚未配置 PREVIEW 环境的 Knox 端点",
            "主端点必须处于 ACTIVE 状态",
            "组件仍被 3 个页面引用，无法删除")) {
            assertThat(messages.byText(text, zh)).as(text).isEqualTo(text);
        }
    }

    /** 插值两侧的空白在**所有支持语言**下都不得被吃掉（不得粘连成 PREVIEWenvironment）。 */
    @Test
    void interpolatedValuesAreNeverGluedToAdjacentWordsInAnyLanguage() {
        String text = "站点尚未配置 PREVIEW 环境的 Knox 端点";
        for (Locale locale : SupportedLocales.SUPPORTED) {
            String rendered = messages.byText(text, locale);
            assertThat(rendered).as(locale.toLanguageTag())
                .containsPattern("\\sPREVIEW\\s")
                .doesNotContain("PREVIEWenvironment");
        }
    }

    /**
     * 稳定键 + 占位符路径（OBS-MS-04 的正确落法）：语序与空格由各语言文案自己决定，
     * 不经中文切分，因此不会出现粘连；法语还须避免 MessageFormat 把 {@code '} 当转义符。
     */
    @Test
    void stableKeyMessageRendersInEverySupportedLanguage() {
        assertThat(messages.get("error.media.env.not.configured", EN, "PREVIEW"))
            .isEqualTo("Site has not configured a Knox endpoint for environment PREVIEW");
        assertThat(messages.get("error.media.env.not.configured",
            Locale.forLanguageTag("zh-CN"), "PREVIEW"))
            .isEqualTo("站点尚未配置 PREVIEW 环境的 Knox 端点");

        for (Locale locale : SupportedLocales.SUPPORTED) {
            assertThat(messages.get("error.media.env.not.configured", locale, "PREVIEW"))
                .as(locale.toLanguageTag())
                .contains("PREVIEW")
                .doesNotContain("{0}")
                .doesNotContain("''");
        }
        // 法语含撇号，MessageFormat 要求写作 ''：渲染结果必须是单撇号
        assertThat(messages.get("error.media.env.not.configured", FR, "PREVIEW"))
            .isEqualTo("Le site n'a pas configuré de point de terminaison Knox pour l'environnement PREVIEW");
    }
}
