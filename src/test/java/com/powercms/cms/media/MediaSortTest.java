package com.powercms.cms.media;

import com.powercms.cms.media.MediaService.AssetInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 媒体库服务端排序与分页回归（FR-KNX-033）。
 *
 * <p>媒体库由「全量拉取 + 前端排序」改为「服务端排序 + 分页」后，有两条不可退让的性质：
 * ① 排序语义需与前端中文自然排序一致（数字段按数值、大小写不敏感）；
 * ② 排序必须是**全序**——否则分页会在页边界重复或跳过资产。本测试同时锁死这两点。
 */
class MediaSortTest {

    private static AssetInfo asset(String filename, long size, String kind, String status) {
        return new AssetInfo(UUID.randomUUID(), UUID.randomUUID(), null, filename, "image/png",
            size, null, 1, status, kind, null, null, null, null);
    }

    private static AssetInfo assetWithId(UUID id, String filename) {
        return new AssetInfo(id, UUID.randomUUID(), null, filename, "image/png",
            1, null, 1, "ACTIVE", "image", null, null, null, null);
    }

    private static List<String> filenames(List<AssetInfo> assets) {
        return assets.stream().map(AssetInfo::filename).toList();
    }

    private static List<AssetInfo> sorted(List<AssetInfo> assets, String mode) {
        List<AssetInfo> target = new ArrayList<>(assets);
        target.sort(java.util.Comparator.comparing(a -> a, MediaSort.comparator(mode)));
        return target;
    }

    // ---------- 自然排序语义（与前端 Intl.Collator numeric 对齐） ----------

    @Test
    void numericSegmentsCompareByValueNotLexicographically() {
        // 纯字典序会得到 a10 < a5，自然序必须是 a5 < a10
        assertThat(MediaSort.compareNatural("a5.png", "a10.png")).isNegative();
        assertThat(MediaSort.compareNatural("img2", "img10")).isNegative();
        assertThat(MediaSort.compareNatural("v1.9.0", "v1.10.0")).isNegative();
    }

    @Test
    void comparisonIsCaseInsensitive() {
        assertThat(MediaSort.compareNatural("Apple", "banana")).isNegative();
        assertThat(MediaSort.compareNatural("apple", "Apple")).isZero();
        assertThat(MediaSort.compareNatural("DIN-Bold.otf", "din-bold.otf")).isZero();
    }

    @Test
    void leadingZerosDoNotChangeNumericValue() {
        // 与前端 Intl.Collator(numeric:true) 实测一致：a1 与 a01 视为相等，
        // 全序由 ID 兜底保证（见 comparatorIsTotalEvenForIdenticalDisplayFields）
        assertThat(MediaSort.compareNatural("a1", "a01")).isZero();
    }

    @Test
    void shorterFilenameSortsFirstWhenPrefixEqual() {
        assertThat(MediaSort.compareNatural("logo", "logo-dark")).isNegative();
    }

    @Test
    void nullIsTreatedAsEmptyString() {
        assertThat(MediaSort.compareNatural(null, "a")).isNegative();
        assertThat(MediaSort.compareNatural("a", null)).isPositive();
        assertThat(MediaSort.compareNatural(null, null)).isZero();
    }

    /**
     * 与前端 {@code Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })} 的**实测**结果对照。
     * 期望值取自 Node 测量（非推测），用于锁定 Java 侧等价实现不漂移。
     */
    @Test
    void matchesFrontendIntlCollatorReferenceTable() {
        // {左, 右, 期望符号}
        Object[][] table = {
            {"a1", "a01", 0},
            {"a5.png", "a10.png", -1},
            {"Apple", "banana", -1},
            {"apple", "Apple", 0},
            {"logo", "logo-dark", -1},
            {"DIN-Bold.otf", "din-bold.otf", 0},
            {"img2", "img10", -1},
            {"v1.9.0", "v1.10.0", -1},
            {"a2.png", "a10.png", -1},
            {"A10", "a9", 1},
            {"x", "", 1},
        };
        for (Object[] row : table) {
            String left = (String) row[0];
            String right = (String) row[1];
            int expected = (Integer) row[2];
            assertThat(Integer.signum(MediaSort.compareNatural(left, right)))
                .as("compareNatural(%s, %s) 应与前端 Intl.Collator 一致", left, right)
                .isEqualTo(expected);
            assertThat(Integer.signum(MediaSort.compareNatural(right, left)))
                .as("compareNatural(%s, %s) 应反对称", right, left)
                .isEqualTo(-expected);
        }
    }

    // ---------- 各排序模式 ----------

    @Test
    void filenameAscIsDefaultAndNumericAware() {
        var list = List.of(asset("a10.png", 1, "image", "ACTIVE"),
            asset("a5.png", 1, "image", "ACTIVE"),
            asset("a2.png", 1, "image", "ACTIVE"));
        assertThat(filenames(sorted(list, null))).containsExactly("a2.png", "a5.png", "a10.png");
        assertThat(filenames(sorted(list, "bogus-mode"))).containsExactly("a2.png", "a5.png", "a10.png");
    }

    @Test
    void filenameDescReversesOrder() {
        var list = List.of(asset("a5.png", 1, "image", "ACTIVE"), asset("a10.png", 1, "image", "ACTIVE"));
        assertThat(filenames(sorted(list, "filename-desc"))).containsExactly("a10.png", "a5.png");
    }

    @Test
    void sizeModesOrderCorrectly() {
        var list = List.of(asset("big.png", 900, "image", "ACTIVE"),
            asset("small.png", 10, "image", "ACTIVE"),
            asset("mid.png", 100, "image", "ACTIVE"));
        assertThat(filenames(sorted(list, "size-asc"))).containsExactly("small.png", "mid.png", "big.png");
        assertThat(filenames(sorted(list, "size-desc"))).containsExactly("big.png", "mid.png", "small.png");
    }

    /** kind/status 有大量并列，必须以文件名收尾才能得到稳定顺序。 */
    @Test
    void kindAndStatusGroupWithFilenameTieBreak() {
        var list = List.of(asset("b.png", 1, "video", "ACTIVE"),
            asset("a.png", 1, "image", "ACTIVE"),
            asset("c.png", 1, "image", "ACTIVE"));
        assertThat(filenames(sorted(list, "kind-asc"))).containsExactly("a.png", "c.png", "b.png");

        var byStatus = List.of(asset("b.png", 1, "image", "PROCESSING"),
            asset("a.png", 1, "image", "ACTIVE"),
            asset("c.png", 1, "image", "ACTIVE"));
        // ACTIVE < PROCESSING，组内按文件名
        assertThat(filenames(sorted(byStatus, "status-asc"))).containsExactly("a.png", "c.png", "b.png");
    }

    // ---------- 全序（分页正确性的前提） ----------

    /**
     * 同名同大小的不同资产（分页下必须仍可比较）不得让比较器返回 0——
     * 否则 {@code List.sort} 结果不稳定，相邻页可能重复或漏掉资产。
     */
    @Test
    void comparatorIsTotalEvenForIdenticalDisplayFields() {
        var first = assetWithId(UUID.fromString("00000000-0000-0000-0000-000000000001"), "same.png");
        var second = assetWithId(UUID.fromString("00000000-0000-0000-0000-000000000002"), "same.png");

        for (String mode : MediaSort.MODES) {
            var comparator = MediaSort.comparator(mode);
            assertThat(comparator.compare(first, second))
                .as("模式 %s 必须能区分「展示字段完全相同」的两行", mode)
                .isNotZero();
            assertThat(comparator.compare(second, first))
                .as("模式 %s 必须反对称", mode)
                .isEqualTo(-comparator.compare(first, second));
        }
    }

    /** 排序必须可重复：同一输入两次排序结果一致（分页依赖稳定顺序）。 */
    @Test
    void sortingIsDeterministicAcrossRuns() {
        List<AssetInfo> list = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            list.add(asset("img" + (i % 7) + ".png", i % 5, i % 3 == 0 ? "video" : "image",
                i % 2 == 0 ? "ACTIVE" : "PROCESSING"));
        }
        for (String mode : MediaSort.MODES) {
            var comparator = MediaSort.comparator(mode);
            List<AssetInfo> a = new ArrayList<>(list);
            List<AssetInfo> b = new ArrayList<>(list);
            a.sort(java.util.Comparator.comparing(x -> x, comparator));
            java.util.Collections.reverse(b);
            b.sort(java.util.Comparator.comparing(x -> x, comparator));
            assertThat(a.stream().map(AssetInfo::id).toList())
                .as("模式 %s 对同一集合的排序结果必须与输入顺序无关", mode)
                .isEqualTo(b.stream().map(AssetInfo::id).toList());
        }
    }

    // ---------- 分页切片 ----------

    @Test
    void sliceCoversAllItemsExactlyOnceAcrossPages() {
        List<String> all = List.of("a", "b", "c", "d", "e", "f", "g");
        List<String> seen = new ArrayList<>();
        int page = 1;
        boolean hasMore = true;
        while (hasMore) {
            var slice = MediaSort.slice(all, page, 3);
            seen.addAll(slice.items());
            hasMore = slice.hasMore();
            page++;
        }
        assertThat(seen).containsExactlyElementsOf(all);
        assertThat(page).isEqualTo(4); // 3 页 + 1 次空页收敛
    }

    @Test
    void sliceReportsTotalAndHasMore() {
        var first = MediaSort.slice(List.of("a", "b", "c", "d", "e"), 1, 2);
        assertThat(first.items()).containsExactly("a", "b");
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.hasMore()).isTrue();

        var last = MediaSort.slice(List.of("a", "b", "c", "d", "e"), 3, 2);
        assertThat(last.items()).containsExactly("e");
        assertThat(last.hasMore()).isFalse();
    }

    /** 前端在筛选/删除后可能带旧页码请求，越界必须回空页而不是抛异常。 */
    @Test
    void sliceReturnsEmptyPageWhenPageBeyondEnd() {
        var slice = MediaSort.slice(List.of("a", "b"), 99, 10);
        assertThat(slice.items()).isEmpty();
        assertThat(slice.total()).isEqualTo(2);
        assertThat(slice.hasMore()).isFalse();
    }

    @Test
    void sliceClampsPageAndSizeToSafeRange() {
        assertThat(MediaSort.slice(List.of("a", "b"), 0, 1).page()).isEqualTo(1);
        assertThat(MediaSort.slice(List.of("a", "b"), -5, 1).items()).containsExactly("a");
        assertThat(MediaSort.slice(List.of("a", "b"), 1, 0).size()).isEqualTo(1);
        // size 超上限不得变相全量拉取
        assertThat(MediaSort.slice(List.of("a", "b"), 1, 100_000).size())
            .isEqualTo(MediaSort.MAX_PAGE_SIZE);
    }

    @Test
    void sliceHandlesEmptySource() {
        var slice = MediaSort.slice(List.of(), 1, 24);
        assertThat(slice.items()).isEmpty();
        assertThat(slice.total()).isZero();
        assertThat(slice.hasMore()).isFalse();
    }
}
