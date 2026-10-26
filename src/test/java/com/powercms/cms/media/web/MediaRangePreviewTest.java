package com.powercms.cms.media.web;

import com.powercms.cms.media.LocalMediaStorage;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 内容检索 → 数字媒体内容预览：Range 分片与中文文件名响应头。
 *
 * <p>缺陷现象：挂载的媒体预览报 500，直接下载却正常。
 * <ol>
 *   <li>控制器按协议自己切 {@code ResponseEntity<ResourceRegion>}，方法返回类型只能写成
 *       {@code ResponseEntity<?>}，类型擦除后 {@code AbstractMessageConverterMethodProcessor}
 *       取到的 targetType 是<b>通配符</b>；而
 *       {@code ResourceRegionHttpMessageConverter#canWrite(Type, Class, MediaType)}
 *       只认 {@code Class} / {@code ParameterizedType}，对 {@code WildcardType} 直接
 *       {@code return false} → {@code HttpMessageNotWritableException: No converter for
 *       [class ...ResourceRegion]} → 500。转换器其实一直在链上，是<b>被拒之门外</b>。
 *       故改为返回 {@code ResponseEntity<Resource>}，把 {@code Range} 交给 Spring MVC
 *       自动改写成 206；带 Range 才是常态（{@code <video>} 必发），不带 Range 的整文件
 *       下载反而 200——这正是“预览报 500、下载却正常”的成因；</li>
 *   <li>文件名为中文时，裸的 {@code filename="统计..."} 超出 HTTP 头的 0–255 字符范围，
 *       Tomcat 会告警并<b>丢弃整个 Content-Disposition</b>，下载名随之丢失。</li>
 * </ol>
 *
 * <p>本用例用「Spring Boot 默认转换器 + {@link MediaDeliveryWebConfig}」搭 MockMvc，
 * 即与线上应用完全一致的转换器链，删除注册配置会导致编译/断言失败。
 */
class MediaRangePreviewTest {

    private static final int TOTAL = 4096;
    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID ASSET_ID = UUID.randomUUID();

    private byte[] payload;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        payload = new byte[TOTAL];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        Path file = Files.createTempFile("media-range-preview", ".mp4");
        Files.write(file, payload);

        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setFilename("媒体预览测试.mp4");
        asset.setMimeType("video/mp4");

        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        when(assets.findByIdAndSiteId(ASSET_ID, SITE_ID)).thenReturn(Optional.of(asset));

        LocalMediaStorage storage = mock(LocalMediaStorage.class);
        when(storage.resolveFile(asset)).thenReturn(Optional.of(file));

        // 与线上完全一致：Spring Boot 默认转换器，再叠加 MediaDeliveryWebConfig 的注册逻辑。
        List<HttpMessageConverter<?>> converters =
            new ArrayList<>(new HttpMessageConverters().getConverters());
        new MediaDeliveryWebConfig().extendMessageConverters(converters);

        LocalMediaController controller = new LocalMediaController(storage, assets);
        mvc = standaloneSetup(controller)
            .setMessageConverters(converters.toArray(new HttpMessageConverter<?>[0]))
            .build();
    }

    @Test
    void resourceRegionConverterIsRegisteredExactlyOnce() {
        List<HttpMessageConverter<?>> converters =
            new ArrayList<>(new HttpMessageConverters().getConverters());
        boolean before = converters.stream().anyMatch(c -> c instanceof ResourceRegionHttpMessageConverter);

        new MediaDeliveryWebConfig().extendMessageConverters(converters);

        assertTrue(converters.stream().anyMatch(c -> c instanceof ResourceRegionHttpMessageConverter),
            "MediaDeliveryWebConfig 必须把 ResourceRegionHttpMessageConverter 加入转换器链，"
                + "否则 /media-preview 的 Range 请求将返回 500（Boot 默认集合是否已含它="
                + before + "）。");
        new MediaDeliveryWebConfig().extendMessageConverters(converters);
        assertEquals(1, converters.stream()
            .filter(c -> c instanceof ResourceRegionHttpMessageConverter).count(),
            "重复注册会产生两个分片转换器");
    }

    @Test
    void rangeRequestReturnsPartialContent() throws Exception {
        MvcResult result = mvc.perform(get("/media-preview/{siteId}/{assetId}", SITE_ID, ASSET_ID)
                .header(HttpHeaders.RANGE, "bytes=0-1023"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-1023/" + TOTAL))
            .andReturn();

        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "必须回传媒体 Content-Type");
        assertTrue(contentType.startsWith("video/mp4"), contentType);
        assertArrayEquals(Arrays.copyOf(payload, 1024),
            result.getResponse().getContentAsByteArray(), "分片内容必须是文件前 1024 字节");
    }

    @Test
    void laterRangeRequestReturnsMiddleSlice() throws Exception {
        MvcResult result = mvc.perform(get("/media-preview/{siteId}/{assetId}", SITE_ID, ASSET_ID)
                .header(HttpHeaders.RANGE, "bytes=2048-3071"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2048-3071/" + TOTAL))
            .andReturn();

        assertArrayEquals(Arrays.copyOfRange(payload, 2048, 3072),
            result.getResponse().getContentAsByteArray());
    }

    @Test
    void mediaFileEndpointAlsoServesRanges() throws Exception {
        // /media-file 曾因仍调用旧的三参 serve(...)（连带 ResponseEntity<?> 返回类型）而 500。
        MvcResult result = mvc.perform(get("/media-file/{siteId}/{assetId}", SITE_ID, ASSET_ID)
                .header(HttpHeaders.RANGE, "bytes=100-199"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 100-199/" + TOTAL))
            .andReturn();

        assertArrayEquals(Arrays.copyOfRange(payload, 100, 200),
            result.getResponse().getContentAsByteArray(), "/media-file 的分片内容必须与文件对应区间一致");
    }

    @Test
    void requestWithoutRangeServesWholeFile() throws Exception {
        MvcResult result = mvc.perform(get("/media-preview/{siteId}/{assetId}", SITE_ID, ASSET_ID))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andReturn();

        assertArrayEquals(payload, result.getResponse().getContentAsByteArray(),
            "不带 Range 的整文件下载必须保持 200 原样");
    }

    @Test
    void chineseFilenameIsPercentEncodedInsteadOfRawHeader() throws Exception {
        MvcResult result = mvc.perform(get("/media-preview/{siteId}/{assetId}", SITE_ID, ASSET_ID))
            .andExpect(status().isOk())
            .andReturn();

        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition, "Content-Disposition 不得缺失");
        assertTrue(disposition.chars().allMatch(c -> c < 128),
            "HTTP 头只能承载 0-255 字符，出现非 ASCII 会被 Tomcat 判非法并丢弃整个头：" + disposition);
        assertTrue(disposition.startsWith("inline; filename=\""), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''%E5%AA%92%E4%BD%93"),
            "媒体两字的 UTF-8 百分号编码必须出现在 filename*：" + disposition);
        assertTrue(disposition.endsWith(".mp4"), disposition);
    }
}