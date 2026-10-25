package com.powercms.cms.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

/**
 * 富文本富媒体净化（图片 / 视频 / 表格 / 字体颜色）。
 *
 * <p>两段式：① jsoup 结构白名单（标签 / 属性面）；② URL 与 style 声明后校验——jsoup 的
 * {@code addProtocols} 无法放行站内相对交付路径（{@code /media-file} 等），因此本类不启用
 * 协议过滤，改为对每个 URL 属性逐一判词法（http(s) / mailto / # 与站内媒体交付前缀）；
 * 对 {@code style} 仅保留白名单属性（颜色 / 表格边框 / 内边距 / 对齐 / 尺寸）且值必须是
 * 安全词法原子序列（长度 / 颜色字面量 / 样式关键字；拒绝 url() / expression() / calc() 等注入面）。
 *
 * <p>接入点：UVE 内联编辑（{@code ContentService.updateInlineField} 的 html 格式）与
 * {@code editableText} 的 full 模式（{@code PageRenderService.sanitizeInlineHtml}）。
 * 内容主保存路径与交付主渲染路径不二次转义（所见即所存），本类只负责确实执行净化的回写通道。
 */
public final class RichTextSanitizer {

    private RichTextSanitizer() {
    }

    /** 结构白名单：排版标签 + 表格 + 图片 / 视频 / iframe 富媒体面（刻意不含 script 等活动内容）。 */
    private static final Safelist RICH_MEDIA = new Safelist()
        .addTags(
            "p", "br", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6",
            "strong", "b", "em", "i", "u", "s", "strike", "del", "ins",
            "blockquote", "ul", "ol", "li", "a", "img", "pre", "code", "hr",
            "sub", "sup", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "caption", "col", "colgroup", "figure", "figcaption",
            "video", "source", "iframe")
        .addAttributes("a", "href", "title", "target", "rel")
        .addAttributes("img", "src", "alt", "title", "width", "height", "style")
        .addAttributes("video", "src", "poster", "controls", "width", "height", "preload", "style")
        .addAttributes("source", "src", "type")
        .addAttributes("iframe", "src", "width", "height", "frameborder", "allowfullscreen", "allow", "title", "loading", "style")
        .addAttributes("span", "style")
        .addAttributes("td", "style", "colspan", "rowspan", "align")
        .addAttributes("th", "style", "colspan", "rowspan", "align")
        .addAttributes("table", "style", "border", "width", "cellpadding", "cellspacing")
        .addAttributes("tr", "style")
        .addAttributes("caption", "style")
        .addAttributes("p", "style")
        .addAttributes("div", "style");

    /** 绝对地址面：http(s)。 */
    private static final Pattern ABSOLUTE = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);

    /** 站内媒体交付前缀（PlayDeliveryController 公开端点）。 */
    private static final Set<String> MEDIA_PREFIXES = Set.of("/media-file/", "/media-thumb/", "/media-play/");

    /** 安全颜色字面量已并入 {@link #VALUE_ATOM}（hex / rgb(a) / hsl(a) / 命名颜色 / inherit）。 */

    /** style property whitelist: font look + table borders + box model padding/sizing + alignment
     + object alignment/flow (display/margin, used by editor object align and resize). */
    private static final Set<String> STYLE_PROPS = Set.of(
        "color", "background-color",
        "border", "border-width", "border-style", "border-color", "border-collapse",
        "border-top", "border-right", "border-bottom", "border-left",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "text-align", "vertical-align", "font-weight", "font-style",
        "width", "max-width", "height",
        "display", "margin", "margin-top", "margin-right", "margin-bottom", "margin-left");

    /**
     * 值原子：长度 / 颜色字面量（含 rgb(a)/hsl(a) 括号形态）/ 样式关键字 / 命名颜色。
     * 括号仅出现在安全颜色函数内——{@code url()} / {@code expression()} / {@code calc()}
     * 等无法成为原子，整条声明即被拒绝。
     */
    private static final String VALUE_ATOM =
        "(?:0|auto|inherit|initial|normal|bold|bolder|lighter|[1-9]00|italic|oblique"
            + "|none|hidden|dotted|dashed|solid|double|groove|ridge|inset|outset"
            + "|collapse|separate|left|right|center|justify|start|end|top|middle|bottom|baseline|sub|super"
            + "|#[0-9a-fA-F]{3,8}|rgba?\\([\\d\\s.,%]+\\)|hsla?\\([\\d\\s.,%]+\\)"
            + "|\\d+(?:\\.\\d+)?(?:px|em|rem|%|pt)"
            + "|[a-zA-Z]+)";

    /** 安全声明值：1..6 个原子（覆盖 border 三段、padding 四段等简写）。 */
    private static final Pattern STYLE_VALUE =
        Pattern.compile(VALUE_ATOM + "(?:\\s+" + VALUE_ATOM + "){0,5}");

    /**
     * 响应式媒体比例（{@code aspect-ratio}）：CSS 的 {@code <ratio>} 形式（{@code 16 / 9}、{@code 1.777}）
     * 或 {@code auto}。
     *
     * <p>为什么要单列：播放代码的自适应 iframe 依赖该属性在「容器无固定高度」时撑出高度，
     * 而通用值原子不认斜杠写法，混进 {@link #STYLE_VALUE} 又会放宽其它属性的词法（如 {@code border: 1/2}）。
     */
    private static final Pattern ASPECT_RATIO =
        Pattern.compile("^(?:auto|\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?)?)$");

    /**
     * 净化富文本 HTML：保留排版 / 表格 / 图片 / 视频 / 字体颜色，
     * 剔除脚本、事件属性、危险协议 URL 与越权 style 声明。
     */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) return "";
        String cleaned = Jsoup.clean(html, "", RICH_MEDIA,
            new Document.OutputSettings().prettyPrint(false));
        Document doc = Jsoup.parseBodyFragment(cleaned);
        for (Element el : doc.select("a[href]")) {
            if (!safeLink(el.attr("href"))) el.removeAttr("href");
        }
        for (Element el : doc.select("[src]")) {
            if (!safeSource(el.tagName(), el.attr("src"))) el.removeAttr("src");
        }
        for (Element el : doc.select("video[poster]")) {
            if (!safeSource("video", el.attr("poster"))) el.removeAttr("poster");
        }
        for (Element el : doc.select("[style]")) {
            String filtered = filterStyle(el.attr("style"));
            if (filtered.isEmpty()) el.removeAttr("style");
            else el.attr("style", filtered);
        }
        // 失去 src 的媒体壳没有渲染意义，直接去掉，避免交付页留下空框
        for (Element el : doc.select("iframe:not([src]),video:not([src]),source:not([src]),img:not([src])")) {
            el.remove();
        }
        return doc.body().html();
    }

    /** 链接面：http(s) / mailto / 锚点 / 站内绝对路径（拒绝 //host 协议相对与 javascript: 等）。 */
    private static boolean safeLink(String href) {
        String v = href == null ? "" : href.trim();
        if (v.isEmpty()) return false;
        return ABSOLUTE.matcher(v).matches()
            || v.toLowerCase(Locale.ROOT).startsWith("mailto:")
            || v.startsWith("#")
            || (v.startsWith("/") && !v.startsWith("//"));
    }

    /** 媒体源面：图片/视频/音频源允许 http(s) 与站内媒体交付前缀；iframe 仅放站内播放器（见下）。 */
    private static boolean safeSource(String tag, String src) {
        String v = src == null ? "" : src.trim();
        if (v.isEmpty() || v.startsWith("//")) return false;
        // DEF-M12-012：iframe 是唯一能引入第三方文档的标签，外部 http(s) 页面嵌入会带来
        // 点击劫持与内容注入面（用例要求存储值不含危险片段），故只放行站内播放器交付路径；
        // 图片/视频等媒体源仍允许 http(s)。
        if ("iframe".equals(tag)) return v.startsWith("/media-play/");
        if (ABSOLUTE.matcher(v).matches()) return true;
        for (String allowed : MEDIA_PREFIXES) {
            if (v.startsWith(allowed)) return true;
        }
        return false;
    }

    /** style 后校验：仅保留白名单属性（颜色/边框/内边距/对齐/尺寸）且值须为安全词法原子序列。 */
    private static String filterStyle(String style) {
        if (style == null || style.isBlank()) return "";
        List<String> kept = new ArrayList<>();
        for (String decl : style.split(";")) {
            int colon = decl.indexOf(':');
            if (colon <= 0) continue;
            String prop = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = decl.substring(colon + 1).trim();
            if ("aspect-ratio".equals(prop)) {
                if (ASPECT_RATIO.matcher(value).matches()) kept.add(prop + ": " + value);
            } else if (STYLE_PROPS.contains(prop) && STYLE_VALUE.matcher(value).matches()) {
                kept.add(prop + ": " + value);
            }
        }
        return String.join("; ", kept);
    }
}
