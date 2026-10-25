package com.powercms.cms.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 范围过滤的编码与解析（{@code FR-AI-KB-045}）。
 *
 * <p>范围过滤是本仓库**新增的过滤原语**（此前只有 term/terms/prefix），
 * 因此这里逐条固定它的编码约定与容错行为。
 */
class RangeFilterTest {

    @Test
    @DisplayName("编码：固定顺序输出，同一组边界必得同一字符串")
    void encodingIsOrderStable() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("lte", 5);
        a.put("gte", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("gte", 1);
        b.put("lte", 5);

        assertThat(RangeFilter.of(a))
            .as("Map 迭代顺序不同却产生不同字符串，会让缓存键与日志比对出现"
                + "「同样的过滤看起来不同」")
            .isEqualTo(RangeFilter.of(b));
        assertThat(RangeFilter.of(a)).isEqualTo("_range:gte:1,lte:5");
    }

    @Test
    @DisplayName("便捷构造 between 等于 gte+lte")
    void betweenEqualsGteLte() {
        assertThat(RangeFilter.between(1, 5)).isEqualTo("_range:gte:1,lte:5");
        assertThat(RangeFilter.between(0L, 1000L)).isEqualTo("_range:gte:0,lte:1000");
    }

    @Test
    @DisplayName("解析：数字串归一为数字（否则数值字段上匹配不到任何文档）")
    void numericBoundsAreCoerced() {
        // 前端 JSON 常把数字序列化成字符串；不归一会让数值字段上的 range 匹配不到
        assertThat(RangeFilter.parse("_range:gte:1,lte:5"))
            .containsEntry("gte", 1L).containsEntry("lte", 5L);
        assertThat(RangeFilter.parse("_range:gte:0.5")).containsEntry("gte", 0.5);
    }

    @Test
    @DisplayName("解析：非数字边界保持字符串（日期等按字段类型解析）")
    void nonNumericBoundsStayStrings() {
        assertThat(RangeFilter.parse("_range:gte:2026-01-01,lte:2026-12-31"))
            .containsEntry("gte", "2026-01-01").containsEntry("lte", "2026-12-31");
    }

    @Test
    @DisplayName("解析是宽容的：非法片段被跳过，不抛异常（不因一个坏条件打断检索）")
    void parsingIsLenient() {
        assertThat(RangeFilter.parse("_range:gte:1,,bogus,lt:9"))
            .as("坏片段跳过即可：抛异常会把「一个过滤条件写错」升级成「整个检索 500」")
            .containsEntry("gte", 1L).containsEntry("lt", 9L);
        assertThat(RangeFilter.parse("_range:")).isEmpty();
        assertThat(RangeFilter.parse("not-a-range")).isEmpty();
        assertThat(RangeFilter.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("构造时必须至少给一个边界：空范围会被误认为「过滤生效了」")
    void emptyBoundsRejected() {
        assertThatThrownBy(() -> RangeFilter.of(Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RangeFilter.of(null))
            .isInstanceOf(IllegalArgumentException.class);
        // 只有非法操作符也等于没有有效边界
        assertThatThrownBy(() -> RangeFilter.of(Map.of("between", 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isRange 判别是单点的（看前缀），不受其它编码影响")
    void isRangeDiscriminates() {
        assertThat(RangeFilter.isRange("_range:gte:1")).isTrue();
        assertThat(RangeFilter.isRange("_prefix:DOC")).as("前缀编码不得被误认为范围").isFalse();
        assertThat(RangeFilter.isRange("DOC")).isFalse();
        assertThat(RangeFilter.isRange(null)).isFalse();
        assertThat(RangeFilter.isRange(5)).isFalse();
    }

    /**
     * 判别顺序必须"先范围后前缀"。
     *
     * <p>两者都是字符串编码的过滤：若先判前缀，{@code _range:gte:1} 会被当成前缀值
     * 拼成 {@code prefix: {field: "_range:gte:1"}} —— 一个**永远匹配不到的前缀**，
     * 于是"过滤后一条都没有"，看起来像数据问题。
     */
    @Test
    @DisplayName("范围编码不得被前缀判别吞掉")
    void rangeIsNotSwallowedByPrefixRule() {
        String encoded = RangeFilter.between(1, 5);
        boolean looksLikePrefix = encoded.startsWith(SearchIndexStore.PREFIX_FILTER_MARK);

        assertThat(looksLikePrefix)
            .as("范围编码不能以前缀标记开头，否则先判前缀的实现在语义上就会分叉")
            .isFalse();
        assertThat(RangeFilter.isRange(encoded)).isTrue();
    }
}
