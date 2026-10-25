package com.powercms.cms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 富文本富媒体净化回归（图片 / 视频 / 表格 / 字体颜色）。
 *
 * <p>锁定两件事：① 富媒体面（img/video/iframe/table/span[color]）不被白名单误伤；
 * ② 脚本、事件属性、危险协议 URL 与越权 style 声明一律剔除。
 */
class RichTextSanitizerTest {

    @Test
    void keepsImageVideoIframeTableAndColorMarkup() {
        String html = "<p>hi <span style=\"color: rgb(255, 0, 0);\">red</span></p>"
            + "<img src=\"/media-file/s1/a1?v=2\" alt=\"a.png\">"
            + "<video controls src=\"https://cdn.example.com/a.mp4\" poster=\"/media-thumb/s1/a1\"></video>"
            + "<iframe src=\"/media-play/s1/a1\" width=\"640\" height=\"360\" allowfullscreen></iframe>"
            + "<table><thead><tr><th>h</th></tr></thead><tbody><tr><td style=\"color: #ff0000\">c</td></tr></tbody></table>";

        String out = RichTextSanitizer.sanitize(html);

        assertThat(out).contains("/media-file/s1/a1?v=2")
            .contains("<video")
            .contains("https://cdn.example.com/a.mp4")
            .contains("/media-thumb/s1/a1")
            .contains("<iframe")
            .contains("/media-play/s1/a1")
            .contains("<table>")
            .contains("<th>h</th>")
            .contains("color: rgb(255, 0, 0)")
            .contains("color: #ff0000");
    }

    @Test
    void stripsScriptEventHandlersAndDangerousUrls() {
        String html = "<p onclick=\"x()\">t</p><script>alert(1)</script>"
            + "<iframe src=\"javascript:alert(1)\"></iframe>"
            + "<img src=\"javascript:alert(1)\" onerror=\"x()\">"
            + "<a href=\"javascript:alert(1)\">bad</a><a href=\"/news/ok\">good</a>"
            + "<span style=\"color: #ff0000; background: url(javascript:alert(1))\">s</span>";

        String out = RichTextSanitizer.sanitize(html);

        assertThat(out).doesNotContain("javascript:", "onclick", "onerror", "<script")
            .contains("<a href=\"/news/ok\">good</a>")
            .contains("color: #ff0000")
            .doesNotContain("background");
        // 丢掉 src 的媒体壳被整只移除
        assertThat(out).doesNotContain("<iframe").doesNotContain("<img");
    }

    @Test
    void iframeOnlyAllowsMediaPlayerPathAmongRelativeUrls() {
        assertThat(RichTextSanitizer.sanitize("<iframe src=\"/media-thumb/s1/a1\"></iframe>")).doesNotContain("<iframe");
        assertThat(RichTextSanitizer.sanitize("<iframe src=\"/media-play/s1/a1\"></iframe>")).contains("<iframe");
        assertThat(RichTextSanitizer.sanitize("<iframe src=\"//evil.example/x\"></iframe>")).doesNotContain("<iframe");
    }

    @Test
    void styleKeepsOnlySafeColorDeclarations() {
        String out = RichTextSanitizer.sanitize(
            "<span style=\"color: inherit; background-image: url(https://x/y); color: expression(alert(1))\">t</span>");
        assertThat(out).contains("color: inherit").doesNotContain("background-image").doesNotContain("expression");
    }

    @Test
    void dropsMediaShellWithoutSource() {
        assertThat(RichTextSanitizer.sanitize("<img alt=\"x\"><video controls></video>")).isEmpty();
        assertThat(RichTextSanitizer.sanitize(null)).isEmpty();
        assertThat(RichTextSanitizer.sanitize("  ")).isEmpty();
    }

    @Test
    void keepsTableStructureAndCellAttributes() {
        String out = RichTextSanitizer.sanitize(
            "<table><tbody><tr><td colspan=\"2\" rowspan=\"1\" style=\"color: #0b5394\">a</td>"
                + "<td>b</td></tr><tr><td>1</td><td>2</td></tr></tbody></table>");
        assertThat(out).contains("colspan=\"2\"").contains("rowspan=\"1\"").contains("color: #0b5394");
        assertThat(out).contains("<tr>").contains("<td>b</td>");
    }

    @Test
    void keepsTableBorderPaddingAndLayoutStyles() {
        String html = "<table style=\"border-collapse: collapse; width: 100%;\">"
            + "<thead><tr><th style=\"border: 1px solid #d9d9d9; padding: 4px 8px; "
            + "background-color: #fafafa; text-align: left;\">h</th></tr></thead>"
            + "<tbody><tr><td style=\"border: 1px solid #d9d9d9; padding: 4px 8px;\">c</td></tr></tbody></table>";

        String out = RichTextSanitizer.sanitize(html);

        assertThat(out).contains("border-collapse: collapse")
            .contains("width: 100%")
            .contains("border: 1px solid #d9d9d9")
            .contains("padding: 4px 8px")
            .contains("background-color: #fafafa")
            .contains("text-align: left");
    }

    @Test
    void stripsUnsafeStyleValuesWhileKeepingSafeNeighbours() {
        String html = "<table style=\"width: calc(100% - 2px); border-collapse: collapse\">"
            + "<tr><td style=\"padding: url(https://evil/x); border: 1px solid expression(alert(1)); "
            + "border-top: 2px dotted #c00000\">t</td></tr></table>";

        String out = RichTextSanitizer.sanitize(html);

        assertThat(out).doesNotContain("calc(", "url(", "expression")
            .contains("border-collapse: collapse")
            .contains("border-top: 2px dotted #c00000");
    }

    @Test
    void keepsLegacyTableBorderAttribute() {
        String out = RichTextSanitizer.sanitize(
            "<table border=\"1\" cellpadding=\"4\" width=\"80\"><tr><td>x</td></tr></table>");
        assertThat(out).contains("border=\"1\"").contains("cellpadding=\"4\"").contains("width=\"80\"");
    }

    @Test
    void keepsResponsiveAspectRatioButRejectsBogusValues() {
        // 播放代码的自适应 iframe：容器无固定高度时靠 aspect-ratio 撑开
        String kept = RichTextSanitizer.sanitize(
            "<iframe src=\"/media-play/s1/a1\" style=\"display: block; width: 100%; height: 100%; "
                + "aspect-ratio: 16 / 9; border: 0;\"></iframe>");
        assertThat(kept).contains("aspect-ratio: 16 / 9")
            .contains("width: 100%")
            .contains("display: block");

        // 非 <ratio> 写法一律剔除，避免把该属性当成任意值通道
        String dropped = RichTextSanitizer.sanitize(
            "<iframe src=\"/media-play/s1/a1\" style=\"aspect-ratio: url(https://evil/x)\"></iframe>");
        assertThat(dropped).doesNotContain("aspect-ratio").doesNotContain("evil");
    }

    @Test
    void keepsObjectAlignmentDisplayAndMarginStyles() {
        // editor object align writes display:block + margin auto pair; resize writes width/height
        String html = "<p><img src=\"/media-file/s1/a1\" style=\"display: block; margin-left: auto; "
            + "margin-right: auto; width: 320px; height: 180px\"></p>"
            + "<table style=\"border-collapse: collapse; width: 60%; margin: 0 auto;\">"
            + "<tbody><tr><td style=\"width: 120px; height: 40px;\"><br></td></tr></tbody></table>";

        String out = RichTextSanitizer.sanitize(html);

        assertThat(out).contains("display: block")
            .contains("margin-left: auto")
            .contains("margin-right: auto")
            .contains("margin: 0 auto")
            .contains("width: 320px")
            .contains("height: 180px")
            .contains("width: 60%")
            .contains("width: 120px")
            .contains("height: 40px");
    }
}
