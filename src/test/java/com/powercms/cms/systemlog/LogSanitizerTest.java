package com.powercms.cms.systemlog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-LOG-RT-007/009、FR-LOG-AC-007、NFR-LOG-007 脱敏规则单测（AC-LOG-003 / AC-LOG-026）。
 */
class LogSanitizerTest {

    @Test
    void masksPasswordTokensInJson() {
        String in = "{\"username\":\"alice\",\"password\":\"P@ssw0rd123\",\"token\":\"abc.def.ghi\"}";
        String out = LogSanitizer.sanitize(in);
        assertThat(out).contains("alice").doesNotContain("P@ssw0rd123").doesNotContain("abc.def.ghi");
        assertThat(out).contains(LogSanitizer.MASK);
    }

    @Test
    void masksApiKeyAndAuthorizationAndCookie() {
        String out = LogSanitizer.sanitize("api_key=sk-1234567890; authorization=Bearer eyJhbGciOi; cookie=JSESSIONID=xyz");
        assertThat(out).doesNotContain("sk-1234567890");
        assertThat(out).doesNotContain("eyJhbGciOi");
        assertThat(out).doesNotContain("JSESSIONID=xyz");
    }

    @Test
    void masksBearerSchemeValue() {
        String out = LogSanitizer.sanitize("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertThat(out).contains("Bearer " + LogSanitizer.MASK);
        assertThat(out).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
    }

    @Test
    void masksMobileKeepingFirstThreeLastFour() {
        String out = LogSanitizer.sanitize("联系手机 13812345678 处理");
        assertThat(out).contains("138****5678");
        assertThat(out).doesNotContain("13812345678");
    }

    @Test
    void masksIdCardKeepingFirstSixLastFour() {
        String out = LogSanitizer.sanitize("身份证 110101199001011234 校验");
        assertThat(out).contains("110101********1234");
        assertThat(out).doesNotContain("199001011234");
    }

    @Test
    void aiPromptAndAnswerNeverLogged() {
        // FR-LOG-RT-009（G-03）：prompt/answer 正文一律掩码，仅元信息可留
        String in = "model=bge-m3 elapsedMs=120 prompt=\"请总结这篇文章\" answer=\"本文讲述……\" inputLen=8 outputLen=9";
        String out = LogSanitizer.sanitize(in);
        assertThat(out).doesNotContain("请总结这篇文章");
        assertThat(out).doesNotContain("本文讲述");
        assertThat(out).contains("model=bge-m3");
        assertThat(out).contains("elapsedMs=120");
        assertThat(out).contains("inputLen=8");
    }

    @Test
    void aiPromptInJsonFormMasked() {
        String in = "{\"model\":\"gpt\",\"prompt\":\"敏感提问内容\",\"inputLength\":8}";
        String out = LogSanitizer.sanitize(in);
        assertThat(out).doesNotContain("敏感提问内容");
        assertThat(out).contains("inputLength");
    }

    @Test
    void querySensitiveKeysMaskedButNamesKept() {
        // FR-LOG-AC-007：参数名保留、值置 ***
        String out = LogSanitizer.sanitizeQuery("user=alice&password=secret123&token=abc&size=10");
        assertThat(out).contains("user=alice");
        assertThat(out).contains("password=" + LogSanitizer.MASK);
        assertThat(out).contains("token=" + LogSanitizer.MASK);
        assertThat(out).contains("size=10");
        assertThat(out).doesNotContain("secret123");
    }

    @Test
    void caseInsensitiveKeys() {
        String out = LogSanitizer.sanitizeQuery("PASSWORD=x1&Pwd=y2&Client_Secret=z3");
        assertThat(out).doesNotContain("x1").doesNotContain("y2").doesNotContain("z3");
    }

    @Test
    void nullAndEmptySafe() {
        assertThat(LogSanitizer.sanitize(null)).isNull();
        assertThat(LogSanitizer.sanitize("")).isEmpty();
        assertThat(LogSanitizer.sanitizeQuery(null)).isNull();
    }
}
