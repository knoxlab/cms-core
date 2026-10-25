package com.powercms.cms.platform.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

/**
 * i18n 基础设施（FR-I18N-001/002）：注册 6 语言资源包。
 * <ul>
 *   <li>{@code i18n/messages*.properties} —— 框架层稳定键 + 各语言标点映射；</li>
 *   <li>{@code i18n/legacy*.properties} —— 服务层"中文原文/片段即键"的遗留消息目录。</li>
 * </ul>
 * 语言解析由 {@link UserLocaleFilter} 完成，业务代码通过 {@link Messages} 门面取文案。
 */
@Configuration
public class I18nConfig {

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages", "i18n/legacy");
        source.setDefaultEncoding("UTF-8");
        // 缺键回落 zh-CN 基线，保证任何语言下都有文案
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.forLanguageTag(SupportedLocales.DEFAULT_TAG));
        return source;
    }
}
