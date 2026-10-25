package com.powercms.cms.delivery;

import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态 GraphQL schema 注册表（FR-API-003）：按站点已发布内容模型合成 SDL，
 * 候选构建 + 解析验证通过后原子替换注册视图；模型发布失败不得影响现有 schema。
 */
@Service
public class DynamicSchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicSchemaRegistry.class);

    public record SchemaVersion(UUID siteId, String sdl, long builtAt, String fingerprint) {}

    private final AtomicReference<Map<UUID, SchemaVersion>> view = new AtomicReference<>(Map.of());
    private final ContentTypeRepository contentTypeRepo;

    public DynamicSchemaRegistry(ContentTypeRepository contentTypeRepo) {
        this.contentTypeRepo = contentTypeRepo;
    }

    /**
     * 候选验证后原子切换：先完成全部构建/校验，成功才整体 publish 新视图。
     */
    public SchemaVersion rebuildForSite(UUID siteId) {
        List<ContentTypeEntity> publishedTypes = contentTypeRepo.findBySiteIdOrderByCodeAsc(siteId).stream()
            .filter(ct -> ContentTypeEntity.STATUS_PUBLISHED.equals(ct.getStatus()))
            // 系统内容类型（system.user-profile 等）不进入公开交付 schema（spec 5.2）
            .filter(ct -> !ct.isSystem())
            .toList();
        String sdl = SdlComposer.compose(publishedTypes);
        new graphql.schema.idl.SchemaParser().parse(sdl);
        String fingerprint = sha256(sdl);
        SchemaVersion candidate = new SchemaVersion(siteId, sdl, System.currentTimeMillis(), fingerprint);
        Map<UUID, SchemaVersion> next = new HashMap<>(view.get());
        next.put(siteId, candidate);
        view.set(Map.copyOf(next));
        log.info("GraphQL schema rebuilt for site {} fingerprint={}", siteId, fingerprint.substring(0, 8));
        return candidate;
    }

    public SchemaVersion get(UUID siteId) {
        return view.get().get(siteId);
    }

    static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
