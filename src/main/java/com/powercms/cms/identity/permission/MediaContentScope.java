package com.powercms.cms.identity.permission;

import java.util.List;
import java.util.UUID;

/**
 * 媒体原件 → 引用它的内容条目（FR-ACCESS-005「内容**及其附件**」）。
 *
 * <p><b>为什么需要这一层</b>：媒体资产的父链是 {@code MEDIA → CONTENT（媒体模型）→ CONTENT_TYPE → SITE}，
 * 自动创建的媒体内容项不带栏目/分类，所以「栏目级/分类级」的访问策略（尤其下载总闸）永远走不到
 * 媒体原件上——管理员在栏目里打开「允许下载」，访客下载该栏目文章里的图片仍然被拒。
 * 这里把「哪些内容引用了这个资产」交给策略层，让它把引用内容所在栏目/分类的开关一并纳入判定。</p>
 *
 * <p>实现放在媒体侧（knox 的引用扫描器之上），身份权限层只依赖本接口，避免反向依赖。</p>
 */
public interface MediaContentScope {

    /**
     * 引用该媒体资产的内容条目编号（只含当前生效版本里命中 assetId 的内容，排除归档）。
     * 实现应保证不抛异常：查不出引用时返回空列表，由调用方按「无引用」处理。
     */
    List<UUID> referencingContents(UUID mediaAssetId);
}
