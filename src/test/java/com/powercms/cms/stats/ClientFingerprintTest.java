package com.powercms.cms.stats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-STAT-003/101 验收：IP 匿名化、XFF 还原、UA 解析、机器人识别。
 */
class ClientFingerprintTest {

    @Test
    void maskIpIpv4ZeroesLastOctet() {
        assertThat(ClientFingerprint.maskIp("1.2.3.4")).isEqualTo("1.2.3.0");
    }

    @Test
    void maskIpKeepsLoopbackIntact() {
        // 回环地址没有匿名化价值：末段置零会显示成 127.0.0.0（像采集异常），原样保留
        assertThat(ClientFingerprint.maskIp("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(ClientFingerprint.maskIp("127.3.4.5")).isEqualTo("127.3.4.5");
        assertThat(ClientFingerprint.maskIp("::1")).isEqualTo("::1");
        // 非回环照常脱敏（前缀相同但非 12.0.0.0/8 的边界）
        assertThat(ClientFingerprint.maskIp("12.3.4.5")).isEqualTo("12.3.4.0");
    }

    @Test
    void hashIpIsStableAndSalted() {
        String a = ClientFingerprint.hashIp("1.2.3.4", "salt-a");
        String b = ClientFingerprint.hashIp("1.2.3.4", "salt-a");
        String c = ClientFingerprint.hashIp("1.2.3.4", "salt-b");
        assertThat(a).isEqualTo(b).hasSize(64).isNotEqualTo(c);
        assertThat(a).doesNotContain("1.2.3");
    }

    @Test
    void xffResolvesFromRightByTrustedProxies() {
        // XFF 语义：客户端在最左，代理逐跳向右追加；trustedProxies=2 跳过右侧 2 个可信代理 → 取 8.8.8.8
        assertThat(ClientFingerprint.resolveClientIp("8.8.8.8, 10.0.0.1, 10.0.0.2", "172.16.0.1", 2))
            .isEqualTo("8.8.8.8");
        // 跳数不足 → 退化为直连地址
        assertThat(ClientFingerprint.resolveClientIp("10.0.0.1", "172.16.0.1", 2))
            .isEqualTo("172.16.0.1");
        // 未配置代理层 → 信任直连（不解析 XFF，防伪造）
        assertThat(ClientFingerprint.resolveClientIp("8.8.8.8", "172.16.0.1", 0))
            .isEqualTo("172.16.0.1");
    }

    @Test
    void userAgentParsing() {
        var chrome = ClientFingerprint.parseUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        assertThat(chrome.os()).isEqualTo("Windows");
        assertThat(chrome.browser()).isEqualTo("Chrome");
        assertThat(chrome.browserVersion()).isEqualTo("126.0.0.0");
        assertThat(chrome.deviceType()).isEqualTo("desktop");

        var edge = ClientFingerprint.parseUserAgent(
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0");
        assertThat(edge.browser()).isEqualTo("Edge");

        var mobile = ClientFingerprint.parseUserAgent(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1");
        assertThat(mobile.os()).isEqualTo("iOS");
        assertThat(mobile.deviceType()).isEqualTo("mobile");

        var unknown = ClientFingerprint.parseUserAgent("");
        assertThat(unknown.os()).isEqualTo("未知");
    }

    @Test
    void botDetection() {
        assertThat(ClientFingerprint.isBot("Mozilla/5.0 (compatible; Googlebot/2.1)")).isTrue();
        assertThat(ClientFingerprint.isBot("curl/8.0")).isTrue();
        assertThat(ClientFingerprint.isBot("Mozilla/5.0 Chrome/126.0")).isFalse();
    }
}
