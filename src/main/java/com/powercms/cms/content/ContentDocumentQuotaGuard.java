package com.powercms.cms.content;

import com.powercms.cms.content.repo.ContentItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 内容文档（= 检索索引的构成单位）**规模基线**观测——矩阵 G1/G2。
 *
 * <p><b>为什么需要它</b>：规格 §3 行 G1（管理端全文检索）给社区版的口径是
 * 「≤10 万<b>内容文档</b>（不含媒体元数据与路由）」。该口径此前只靠
 * {@link ContentService#create} 间接约束，一旦存在不经过该方法的写入口
 * （公开表单提交、媒资同步生成内容条目等），检索索引的内容文档规模就没有第二个
 * 观测点了。</p>
 *
 * <p><b>口径</b>（§4.1 三层口径）：社区版基线是官方发行包的建议支撑规模，
 * 不是对 Apache-2.0 源码的使用限制。越过基线时只 {@code log.warn} 并在管理端提示
 * 升级，<b>不拦截写入、不影响检索</b>。</p>
 */
@Component
public class ContentDocumentQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(ContentDocumentQuotaGuard.class);

    /** 社区版建议支撑基线：内容条目 10 万（规格 §4.1）。 */
    public static final long COMMUNITY_CONTENT_ITEMS_BASELINE = 100_000L;

    private final ContentItemRepository items;

    public ContentDocumentQuotaGuard(ContentItemRepository items) {
        this.items = items;
    }

    /**
     * 写入前观测：本次新增 {@code delta} 条内容文档后是否越过社区基线。
     * 只告警，不抛异常。
     *
     * <p>调用方应在**写库之前**调用。</p>
     */
    public void assertCanAdd(long delta) {
        observe(items.count() + Math.max(0, delta));
    }

    /** 是否仍在社区建议基线内（只读判定，供管理端提示升级）。 */
    public boolean hasRoom(long delta) {
        return items.count() + Math.max(0, delta) <= COMMUNITY_CONTENT_ITEMS_BASELINE;
    }

    /**
     * 检索侧观测（矩阵 G1/G2）：全量重建时按**索引里的内容文档条数**独立核对基线，
     * 越界只告警不阻断——检索可用性不得受规模基线影响。
     *
     * @param indexedContentDocs 本次重建写入的内容文档条数（仅内容条目，不含媒体元数据与路由）
     */
    public void observeIndexBaseline(long indexedContentDocs) {
        observe(indexedContentDocs);
    }

    private void observe(long estimatedContentDocs) {
        if (estimatedContentDocs <= COMMUNITY_CONTENT_ITEMS_BASELINE) return;
        log.warn("community baseline: 内容文档 {} 条已超过社区建议支撑基线 {}；"
                + "检索与写入继续可用，超出基线的部署不在官方支持范围内",
            estimatedContentDocs, COMMUNITY_CONTENT_ITEMS_BASELINE);
    }
}
