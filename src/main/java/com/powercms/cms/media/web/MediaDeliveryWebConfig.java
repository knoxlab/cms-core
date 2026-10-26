package com.powercms.cms.media.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 保证 {@link ResourceRegionHttpMessageConverter} 在转换器链里——媒体直出的 HTTP 206 分片要靠它写出。
 *
 * <p><b>为什么它可能缺席：</b>{@code WebMvcConfigurationSupport#getMessageConverters()} 的逻辑是
 * <pre>configureMessageConverters(list);              // 先让 WebMvcConfigurer 填
 * if (list.isEmpty()) addDefaultHttpMessageConverters(list);   // 仅当没填才补默认
 * extendMessageConverters(list);</pre>
 * 一旦 {@code configureMessageConverters} 产出了非空列表，Spring 自带的
 * {@code addDefaultHttpMessageConverters(...)}（{@code ResourceRegion} 转换器只在这里注册）就被跳过，
 * 于是链上没有任何组件能写 {@code ResourceRegion}。这里在 {@code extendMessageConverters} 里补一个，
 * 不论前一步填了什么都会生效。
 *
 * <p><b>不修的后果：</b>浏览器 {@code <video>} / {@code <audio>} 播放前必发 {@code Range: bytes=0-}，
 * Spring MVC 收到后把整段 200 改写成 {@code 206 Partial Content} + {@code ResourceRegion}，
 * 缺转换器就抛 {@code HttpMessageNotWritableException: No converter for [class ...ResourceRegion]
 * with preset Content-Type 'video/mp4'} → {@code /media-preview}、{@code /media-file} 一律 500；
 * 不带 Range 的整文件下载反而 200——这正是“预览报 500、下载却正常”的成因。
 *
 * <p>用 {@code extendMessageConverters} 追加而非 {@code configureMessageConverters}：后者一旦被
 * 任何 {@link WebMvcConfigurer} 实现并返回，就会<b>整体替换</b>默认转换器集合，把 Jackson 一并挤掉。
 */
@Configuration(proxyBeanMethods = false)
public class MediaDeliveryWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(MediaDeliveryWebConfig.class);

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof ResourceRegionHttpMessageConverter) {
                log.info("[media-range] ResourceRegionHttpMessageConverter 已在转换器链中");
                return;
            }
        }
        // 前置注册：其后的 ResourceHttpMessageConverter 只认 Resource 子类、不认 ResourceRegion，
        // 不会抢占；同时保证日后若有人追加转换器，分片转换器仍排在最前。
        converters.add(0, new ResourceRegionHttpMessageConverter());
        log.info("[media-range] 已注册 ResourceRegionHttpMessageConverter，当前共 {} 个转换器",
            converters.size());
    }
}