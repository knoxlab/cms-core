package com.powercms.cms.media;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 媒体库服务端排序与分页（FR-KNX-033）。
 *
 * <p>媒体库列表由客户端全量拉取 + 前端排序改为**服务端分页**后，排序必须随之下沉到服务端：
 * 只对「已加载窗口」排序会得到正确但错位的列表（页内有序、页间无序），是比不排序更糟的假象。
 *
 * <p>本类的比较器是前端 {@code Intl.Collator('zh-CN', {numeric: true, sensitivity: 'base'})}
 * 的 Java 侧等价实现：忽略大小写/重音（{@link Collator#PRIMARY}）、数字段按数值比较
 * （`a5` 排在 `a10` 前）。**近似而非逐字节一致**——浏览器 ICU 与 JDK 的 CJK 排序权重可能
 * 存在细微差异，故纯中文文件名的相对次序在极少数情况下可能与旧版前端排序不同；ASCII
 * 文件名与数字段完全一致。
 *
 * <p>排序结果必须是**全序**：分页依赖稳定顺序，否则同一资产可能在相邻页重复出现或被跳过。
 * 因此每种排序模式都以文件名（自然序）收尾，再以资产 ID 兜底，保证任意两行都可比较。
 */
public final class MediaSort {

    /** 支持的排序模式，与前端 {@code SortMode} 一一对应。 */
    public static final List<String> MODES = List.of(
        "filename-asc", "filename-desc", "size-desc", "size-asc", "kind-asc", "status-asc");

    /** 默认排序（与前端默认值一致）。 */
    public static final String DEFAULT_MODE = "filename-asc";

    /** 单页上限：防止调用方传超大 size 变相全量拉取。 */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 排序比较器需要的大小写/重音不敏感排序规则。
     * {@link Collator} 非线程安全，故按线程持有。
     */
    private static final ThreadLocal<Collator> COLLATORS = ThreadLocal.withInitial(() -> {
        Collator collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.PRIMARY);
        return collator;
    });

    private MediaSort() {
    }

    public static boolean isSupported(String mode) {
        return mode != null && MODES.contains(mode);
    }

    /** 归一化排序模式：未知/空值回落到默认。 */
    public static String normalizeMode(String mode) {
        return isSupported(mode) ? mode : DEFAULT_MODE;
    }

    /**
     * 按模式取得比较器。未知模式按默认（文件名升序）处理，与前端 {@code default:} 分支一致。
     * 返回的比较器保证全序（文件名收尾 + ID 兜底）。
     */
    public static Comparator<MediaService.AssetInfo> comparator(String mode) {
        return switch (normalizeMode(mode)) {
            case "filename-desc" -> withTotalOrder(
                (a, b) -> compareNatural(b.filename(), a.filename()));
            case "size-desc" -> withTotalOrder(
                Comparator.comparingLong(MediaService.AssetInfo::fileSize).reversed());
            case "size-asc" -> withTotalOrder(
                Comparator.comparingLong(MediaService.AssetInfo::fileSize));
            case "kind-asc" -> withTotalOrder(
                (a, b) -> compareNatural(a.kind(), b.kind()));
            case "status-asc" -> withTotalOrder(
                (a, b) -> compareNatural(a.status(), b.status()));
            default -> withTotalOrder(
                (a, b) -> compareNatural(a.filename(), b.filename()));
        };
    }

    /**
     * 补齐全序：主比较器相等时按文件名（自然序）排，再按资产 ID 排。
     * 前端对 kind/status/size 也是以文件名收尾；ID 兜底是本类额外加的确定性保证。
     */
    private static Comparator<MediaService.AssetInfo> withTotalOrder(
        Comparator<MediaService.AssetInfo> primary) {
        return primary
            .thenComparing((a, b) -> compareNatural(a.filename(), b.filename()))
            .thenComparing(a -> a.id().toString());
    }

    /**
     * 自然序比较（`numeric: true` + 大小写不敏感）：
     * 逐字符比较，遇到**两侧同为数字**时按数字段比较（前导零忽略、按长度再字典序 → 等价数值大小）。
     */
    public static int compareNatural(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        Collator collator = COLLATORS.get();
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                int cmp = compareNumericRun(a.substring(startA, i), b.substring(startB, j));
                if (cmp != 0) return cmp;
            } else {
                int cmp = collator.compare(String.valueOf(ca), String.valueOf(cb));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** 数字段按数值比较：去前导零后先比位数、再比字典序（避免大数溢出）。 */
    private static int compareNumericRun(String rawA, String rawB) {
        String a = trimLeadingZeros(rawA);
        String b = trimLeadingZeros(rawB);
        if (a.length() != b.length()) return Integer.compare(a.length(), b.length());
        return a.compareTo(b);
    }

    private static String trimLeadingZeros(String value) {
        int i = 0;
        while (i < value.length() - 1 && value.charAt(i) == '0') i++;
        return value.substring(i);
    }

    /** 分页切片结果：{@code items} 为当前页，{@code total} 为过滤后总数。 */
    public record Slice<T>(List<T> items, int total, int page, int size, boolean hasMore) {}

    /**
     * 对已排序列表切片。
     *
     * <p>{@code page} 越界（超过末页）返回空页且 {@code hasMore=false}，不抛异常——
     * 前端在删除/筛选后可能带着旧页码请求。{@code size} 收敛到 {@code [1, MAX_PAGE_SIZE]}。
     */
    public static <T> Slice<T> slice(List<T> sorted, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);
        int total = sorted == null ? 0 : sorted.size();
        long offsetWide = (long) (safePage - 1) * safeSize;
        int from = (int) Math.min(offsetWide, total);
        int to = Math.min(from + safeSize, total);
        return new Slice<>(new ArrayList<>(sorted.subList(from, to)), total, safePage, safeSize, to < total);
    }
}
