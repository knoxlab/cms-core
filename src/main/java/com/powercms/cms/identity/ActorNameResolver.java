package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 展示层 actor 解析（OBS-11-04）：把「用户 ID 或用户名」统一解析为用户名。
 *
 * <p>同一份业务数据里 actor 的存法并不统一：内容创建 / 保存版本走 {@code Authentication#getName()}
 * （用户名，如 {@code siteadmin.s1}），而 AI 建议池、批量 AI、审计流走
 * {@link com.powercms.cms.audit.AuditService#currentActor()}（用户 ID）。列表接口若原样下发，
 * 界面便会出现「一半用户名、一半 UUID」。此处统一：能查到用户则返回 {@code username}，
 * 查不到（或本身已是用户名 / {@code system} / 空）原样返回。</p>
 *
 * <p>本类只做「展示名」解析，不改写任何落库数据，也不改变筛选语义。</p>
 */
@Service
public class ActorNameResolver {

    private final UserRepository userRepo;

    public ActorNameResolver(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /**
     * 不查库的「原样返回」实例：供无身份库的装配场景（如服务单测的便捷构造器）使用，
     * 行为等价于「所有 actor 都查不到用户」——{@code resolve} 原样返回、{@code resolveAll} 映射回自身。
     */
    public static ActorNameResolver passThrough() {
        return new ActorNameResolver(null);
    }

    /** 单个 actor 解析；列表场景请用 {@link #resolveAll(Collection)} 避免 N+1 查询。 */
    @Transactional(readOnly = true)
    public String resolve(String actor) {
        UUID id = userIdOf(actor);
        if (id == null || userRepo == null) return actor;
        return userRepo.findById(id)
            .map(UserEntity::getUsername)
            .filter(name -> name != null && !name.isBlank())
            .orElse(actor);
    }

    /**
     * 批量解析：一次查库，返回「输入 actor → 展示名」映射；未命中用户时映射回自身。
     * 调用方可直接 {@code map.get(actor)}（不会为 null，null actor 除外）。
     */
    @Transactional(readOnly = true)
    public Map<String, String> resolveAll(Collection<String> actors) {
        Map<String, String> names = new LinkedHashMap<>();
        if (actors == null || actors.isEmpty()) return names;
        Set<UUID> ids = new LinkedHashSet<>();
        for (String actor : actors) {
            if (actor == null) continue;
            names.putIfAbsent(actor, actor);
            UUID id = userIdOf(actor);
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty() || userRepo == null) return names;
        Map<UUID, String> byId = new LinkedHashMap<>();
        userRepo.findAllById(ids).forEach(user -> byId.put(user.getId(), user.getUsername()));
        names.replaceAll((actor, fallback) -> {
            UUID id = userIdOf(actor);
            String name = id == null ? null : byId.get(id);
            return name == null || name.isBlank() ? fallback : name;
        });
        return names;
    }

    /** 仅识别严格形态的 UUID 串（36 位、第 9 位为 {@code -}），避免把普通用户名误判为 ID。 */
    private static UUID userIdOf(String actor) {
        if (actor == null || actor.length() != 36 || actor.charAt(8) != '-') return null;
        try {
            return UUID.fromString(actor);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
