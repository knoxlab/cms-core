package com.powercms.cms.platform.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 公开交付 API 的 CORS 配置（FR-API-006）：
 * allowed-origins 为空时允许任意 Origin 读取公开内容，但不允许携带凭据；
 * 生产环境通过 powercms.gateway.allowed-origins 收紧白名单。
 */
@Configuration
public class GatewayCorsConfig {

    @Bean
    CorsFilter publicApiCorsFilter(@Value("${powercms.gateway.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (origins.isEmpty()) {
            config.addAllowedOriginPattern("*");
        } else {
            config.setAllowedOrigins(origins);
        }
        config.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION,
            "X-PowerCMS-Token", "If-None-Match", "If-Modified-Since",
            // HLS/视频分片播放器会带 Range（Safari 等会先做预检），缺省会被 CORS 拦下
            HttpHeaders.RANGE, HttpHeaders.IF_RANGE));
        // 分片响应的 Content-Range / Accept-Ranges 需要显式暴露，跨域播放器才能做分段取流
        config.setExposedHeaders(List.of(HttpHeaders.CONTENT_RANGE, HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.ACCEPT_RANGES));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/delivery/**", config);
        source.registerCorsConfiguration("/api/v1/public/**", config);
        source.registerCorsConfiguration("/graphql", config);
        // FR-KNX-032：播放代码的三种形态都要能被**其他域名**引用。
        // - /media-play/**：iframe 播放代理页（同时已在 SecurityConfig 关闭 X-Frame-Options）
        // - /media-stream/**：HLS 清单与分片（含清单内改写后的绝对地址）
        // - /media-player-sdk.js：JS SDK 统一加载脚本
        // 注意不注册 /media-file/**：该端点自己固定输出 Access-Control-Allow-Origin: *，
        // 再叠一层白名单会把既有的字体跨源能力收窄。
        source.registerCorsConfiguration("/media-play/**", config);
        source.registerCorsConfiguration("/media-stream/**", config);
        source.registerCorsConfiguration("/media-player-sdk.js", config);
        source.registerCorsConfiguration("/media-thumb/**", config);
        source.registerCorsConfiguration("/media-preview/**", config);
        // FR-KNX-023：进度条悬停预览。图片靠 <img>/CSS 取雪碧图本就不受 CORS 约束，
        // 但 <track kind="metadata" src="/media-vtt/..."> 是 CORS 模式请求，跨域嵌入页
        // 缺这两条注册会被浏览器按同源策略拦掉（表现为控制台 CORS 报错、无悬停预览）。
        source.registerCorsConfiguration("/media-sprite/**", config);
        source.registerCorsConfiguration("/media-vtt/**", config);
        return new CorsFilter(source);
    }
}
