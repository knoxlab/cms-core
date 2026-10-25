package com.powercms.cms.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 游标分页（FR-SEARCH-004「游标分页」）的**不透明游标**编解码。
 *
 * <p>游标是 OpenSearch {@code search_after} 的取值数组（与请求里 {@code sort} 数组一一对应，
 * 含末尾的稳定 tiebreaker），经 JSON + Base64URL 编码后回给调用方。调用方只需原样回传
 * {@code nextCursor}，**不得解析其内部结构** —— 这样排序字段与 tiebreaker 的演进不会破坏已下发的游标。
 *
 * <p>刻意不做「游标内嵌排序签名校验」：游标不携带权限语义（鉴权在 {@code @PreAuthorize} 与
 * 对象级 ACL 两处），篡改游标只可能改变自己的分页位置，不构成越权面；而校验签名会让
 * 「换排序后旧游标失效」变成难以解释的报错。{@code search_after} 在换排序后本就会给出
 * 未定义但安全（无越权）的结果，与 OpenSearch 原生语义一致。
 */
public final class SearchCursor {

    private static final ObjectMapper OM = new ObjectMapper();

    private SearchCursor() {}

    /** 编码 {@code search_after} 取值数组；空数组/空列表返回 null（无下一页）。 */
    public static String encode(List<Object> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(OM.writeValueAsBytes(values));
        } catch (Exception ex) {
            // 编码失败不得影响主检索结果：放弃游标（调用方可退回首屏）
            return null;
        }
    }

    /**
     * 解码游标。
     *
     * @throws BusinessException {@code SEARCH_CURSOR_INVALID}（→ 400）：游标不是本服务下发的形态
     */
    public static List<Object> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return List.of();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor.trim());
            List<Object> values = OM.readValue(new String(raw, StandardCharsets.UTF_8),
                new TypeReference<List<Object>>() {});
            if (values == null || values.isEmpty()) {
                throw new BusinessException(ErrorCodes.SEARCH_CURSOR_INVALID, "分页游标为空");
            }
            return values;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.SEARCH_CURSOR_INVALID,
                "分页游标无法解析，请从第一页重新检索");
        }
    }
}
