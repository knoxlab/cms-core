package com.powercms.cms.media;

import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MediaRemoteFetcher} 的 SSRF 防护与文件名推导单测（FR-AGT-MEDIA-002 的 URL 来源通道）。
 *
 * <p>不发起真实外网请求：只覆盖「请求发出前」的地址/协议校验与纯函数式文件名推导；
 * 重定向拒绝、流式大小上限等需真实上游的分支由端到端与手工验证覆盖（见批次 5 验收报告第七节）。
 */
class MediaRemoteFetcherTest {

    private final MediaRemoteFetcher fetcher = new MediaRemoteFetcher(false);

    @Test
    void rejectsNonHttpSchemes() {
        for (String url : new String[]{"ftp://example.com/a.png", "file:///etc/passwd", "gopher://example.com/x"}) {
            assertThatThrownBy(() -> fetcher.fetch(url, 1024))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持 http/https");
        }
    }

    @Test
    void rejectsUrlWithEmbeddedCredentials() {
        assertThatThrownBy(() -> fetcher.fetch("http://user:secret@example.com/a.png", 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不含凭据");
    }

    @Test
    void rejectsRelativeOrHostlessUrl() {
        assertThatThrownBy(() -> fetcher.fetch("/images/a.png", 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅支持 http/https");
        assertThatThrownBy(() -> fetcher.fetch("http:///a.png", 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不含凭据");
    }

    @Test
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> fetcher.fetch("   ", 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("source_url 不能为空");
    }

    @Test
    void rejectsLoopbackSiteLocalAnyLocalAndMulticastTargets() {
        for (String url : new String[]{
            "http://127.0.0.1/a.png", "http://localhost/a.png", "http://10.1.2.3/a.png",
            "http://192.168.0.10/a.png", "http://172.16.5.5/a.png", "http://0.0.0.0/a.png",
            "http://224.0.0.1/a.png",
        }) {
            assertThatThrownBy(() -> fetcher.fetch(url, 1024))
                .as(url)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内网地址");
        }
    }

    @Test
    void rejectsLinkLocalCloudMetadataAddress() {
        assertThatThrownBy(() -> fetcher.fetch("http://169.254.169.254/latest/meta-data/", 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("内网地址");
    }

    @Test
    void skipsPrivateGuardWhenExplicitlyEnabled() {
        // allow-private=true 时地址校验被显式放开：对不可解析域名只会走到 DNS/连接失败，而非「内网地址」拒绝
        MediaRemoteFetcher trusting = new MediaRemoteFetcher(true);
        assertThatThrownBy(() -> trusting.fetch("http://batch5-nonexistent.invalid/logo.png", 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("内网地址");
    }

    @Test
    void derivesFilenameFromContentDisposition() {
        URI uri = URI.create("https://cdn.example.com/media/raw");

        assertThat(MediaRemoteFetcher.filenameOf(uri, "attachment; filename=\"cover 图.png\""))
            .isEqualTo("cover 图.png");
        assertThat(MediaRemoteFetcher.filenameOf(uri, "attachment; filename=clip.mp4; size=1024"))
            .isEqualTo("clip.mp4");
    }

    @Test
    void derivesFilenameFromUrlPathWithPercentDecoding() {
        assertThat(MediaRemoteFetcher.filenameOfUrl("https://cdn.example.com/media/%E5%B0%81%E9%9D%A2.png"))
            .isEqualTo("封面.png");
    }

    @Test
    void stripsPathTraversalAndBackslashesFromFilename() {
        assertThat(MediaRemoteFetcher.filenameOf(URI.create("https://cdn.example.com/a.png"),
            "attachment; filename=\"../../evil.png\"")).isEqualTo("evil.png");
        assertThat(MediaRemoteFetcher.filenameOfUrl("https://cdn.example.com/media/..%5C..%5Cevil.png"))
            .isEqualTo("evil.png");
    }

    @Test
    void fallsBackToGenericFilenameWhenUrlHasNoLastSegment() {
        assertThat(MediaRemoteFetcher.filenameOfUrl("https://cdn.example.com/")).isEqualTo("download.bin");
        assertThat(MediaRemoteFetcher.filenameOf(URI.create("https://cdn.example.com"), null))
            .isEqualTo("download.bin");
    }

    @Test
    void filenameOfUrlReturnsNullForBlankInput() {
        assertThat(MediaRemoteFetcher.filenameOfUrl(null)).isNull();
        assertThat(MediaRemoteFetcher.filenameOfUrl("  ")).isNull();
    }
}
