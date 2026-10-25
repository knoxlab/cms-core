package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.OrganizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    /**
     * 首期单组织的「当前组织」**唯一口径**（DEF-CFG-01）。
     *
     * <p>写入侧（{@code IdentityServiceImpl.ensureOrganization} → 请求属性 {@code organizationId}）与
     * 读取侧（{@code OrganizationSettingsService.defaultOrganizationId}）必须同源：任一取「无序遍历的
     * 第一行」、另一取「最早创建的一行」，只要库里存在两行组织，就会出现「组织设置写在一个组织、
     * 消费方读另一个组织」的静默失效 —— 域 11 实测 loginIdentifier / 登录页品牌 / 组织默认语言三者
     * 全部不生效，根因就是这里。
     *
     * <p>排序取 {@code createdAt} 升序（最早创建者即当前组织）；{@code createdAt} 相同时以 {@code id}
     * 兜底，保证结果**完全确定**（域 11 的两份「默认组织」createdAt 只差 8ms）。
     */
    @Query("select o.id from OrganizationEntity o order by o.createdAt, o.id")
    List<UUID> findAllIds();
}
