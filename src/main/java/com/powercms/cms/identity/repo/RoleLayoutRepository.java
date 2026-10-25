package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.RoleLayoutEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RoleLayoutRepository extends JpaRepository<RoleLayoutEntity, UUID> {

    List<RoleLayoutEntity> findByRoleId(UUID roleId);

    List<RoleLayoutEntity> findByRoleIdIn(Collection<UUID> roleIds);

    boolean existsByRoleIdAndLayoutId(UUID roleId, UUID layoutId);

    /**
     * 删除角色的全部布局绑定。
     *
     * <p>角色删除属「配置随主体消亡」，布局绑定必须先清（{@code sys_role_layout.role_id} 的外键
     * 无级联删除），否则删除角色会撞外键约束并对外报成 409「唯一约束冲突」（DEF-11-18）。</p>
     */
    void deleteByRoleId(UUID roleId);
}
