package com.powercms.cms.sitepackage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualLayoutHtmlRendererTest {
    @Test
    void outputsOnlySafeDeduplicatedClassesAndClampsWidths() {
        String html = VisualLayoutHtmlRenderer.render("""
            {"rows":[{"classes":["hero","bad_","hero"],"boxes":[
              {"width":0,"classes":"box good bad.class"},
              {"width":99,"classes":["wide"]}
            ]}]}
            """);

        assertThat(html).contains("class=\"hero\"");
        assertThat(html).contains("class=\"box good\" style=\"width:8.333333%;flex:0 0 8.333333%\"");
        assertThat(html).contains("class=\"wide\" style=\"width:100%;flex:0 0 100%\"");
        assertThat(html).doesNotContain("bad_", "bad.class");
    }

    @Test
    void insertsMapAndStringZoneHtmlWithoutEscapingOrUsingZoneKeyAsMarkup() {
        String html = VisualLayoutHtmlRenderer.render("""
            {"rows":[{"boxes":[
              {"width":8,"zoneKey":"main<script>"},
              {"width":4,"zoneKey":"direct"}
            ]}]}
            """, Map.of(
                "main<script>", Map.of("html", "<section>safe result</section>"),
                "direct", "<strong>direct</strong>"
            ));

        assertThat(html).contains("width:66.666667%", "<section>safe result</section>", "<strong>direct</strong>");
        assertThat(html).doesNotContain("<script>", "&lt;section&gt;");
    }

    @Test
    void rendersLeftAndRightSidebarWithConfiguredWidth() {
        String left = VisualLayoutHtmlRenderer.render(
            "{\"sidebar\":\"LEFT\",\"sidebarWidth\":20,\"rows\":[]}",
            Map.of("sidebar", Map.of("html", "<nav>links</nav>")), "<article>page</article>");
        String right = VisualLayoutHtmlRenderer.render(
            "{\"sidebar\":\"RIGHT\",\"sidebarWidth\":40,\"rows\":[]}",
            Map.of("sidebar", "<nav>links</nav>"), "<article>page</article>");

        assertThat(left).contains("sidebar-left", "width:20%;flex:0 0 20%", "width:80%;flex:0 0 80%")
            .containsSubsequence("<aside", "<nav>links</nav>", "<main", "<article>page</article>");
        assertThat(right).contains("sidebar-right", "width:40%;flex:0 0 40%", "width:60%;flex:0 0 60%")
            .containsSubsequence("<main", "<article>page</article>", "<aside", "<nav>links</nav>");
    }

    @Test
    void noSidebarKeepsRowsBeforePageBody() {
        String html = VisualLayoutHtmlRenderer.render(
            "{\"sidebar\":\"NONE\",\"rows\":[{\"boxes\":[{\"zoneKey\":\"main\"}]}]}",
            Map.of("main", Map.of("html", "<div>zone</div>")), "<p>page body</p>");

        assertThat(html).doesNotContain("visual-layout-sidebar")
            .containsSubsequence("<div>zone</div>", "<p>page body</p>");
    }

    @Test
    void rendersMultipleBoxZonesInOrderAndDeduplicatesKeys() {
        String html = VisualLayoutHtmlRenderer.render("""
            {"rows":[{"boxes":[{"zoneKey":"legacy","zoneKeys":[" first ",null,"",7,"second","first"]}]}]}
            """, Map.of("first", "<i>first</i>", "second", "<i>second</i>", "legacy", "<i>legacy</i>"));

        assertThat(html).containsSubsequence("<i>first</i>", "<i>second</i>")
            .doesNotContain("<i>legacy</i>");
    }

    @Test
    void fallsBackToLegacyBoxZoneWhenZoneKeysMissingOrEmpty() {
        String missing = VisualLayoutHtmlRenderer.render(
            "{\"rows\":[{\"boxes\":[{\"zoneKey\":\"legacy\"}]}]}", Map.of("legacy", "<b>legacy</b>"));
        String empty = VisualLayoutHtmlRenderer.render("""
            {"rows":[{"boxes":[{"zoneKey":"legacy","zoneKeys":[null,"  "]}]}]}
            """, Map.of("legacy", "<b>legacy</b>"));

        assertThat(missing).contains("<b>legacy</b>");
        assertThat(empty).contains("<b>legacy</b>");
    }

    @Test
    void rendersMultipleSidebarZonesAndFallsBackToSidebarConvention() {
        String multiple = VisualLayoutHtmlRenderer.render(
            "{\"sidebar\":\"RIGHT\",\"sidebarZoneKeys\":[\"tools\",\"promo\",\"tools\"],\"rows\":[]}",
            Map.of("tools", "<nav>tools</nav>", "promo", "<aside>promo</aside>"));
        String fallback = VisualLayoutHtmlRenderer.render(
            "{\"sidebar\":\"LEFT\",\"sidebarZoneKeys\":[],\"rows\":[]}",
            Map.of("sidebar", "<nav>legacy sidebar</nav>"));

        assertThat(multiple).containsSubsequence("<nav>tools</nav>", "<aside>promo</aside>");
        assertThat(fallback).contains("<nav>legacy sidebar</nav>");
    }

    @Test
    void invalidJsonFallsBackToPageBodyAndCompatibilityMethodReturnsEmpty() {
        assertThat(VisualLayoutHtmlRenderer.render("not-json")).isEmpty();
        assertThat(VisualLayoutHtmlRenderer.render("not-json", Map.of(), "<p>page body</p>"))
            .isEqualTo("<p>page body</p>");
    }
}
