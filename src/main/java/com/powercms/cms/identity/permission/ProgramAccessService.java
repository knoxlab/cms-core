package com.powercms.cms.identity.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.CidrMatcher;
import com.powercms.cms.identity.permission.entity.ProgramAccessPolicyEntity;
import com.powercms.cms.identity.permission.repo.ProgramAccessPolicyRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 条件维度解析与判定（FR-ACCESS-003/004/005）。
 * 文件夹链与分类链各自沿父级取第一个非 NULL 维度，两侧都有定义时取更严的结果。
 */
@Service
public class ProgramAccessService {

    private static final Set<String> POLICY_TYPES = Set.of(
        ObjectType.SITE, ObjectType.FOLDER, ObjectType.TAXONOMY,
        ObjectType.CONTENT_TYPE, ObjectType.CONTENT, ObjectType.MEDIA);

    private final ProgramAccessPolicyRepository policies;
    private final PermissionableResolver resolver;
    private final ContentItemRepository contents;
    private final GeoResolver geoResolver;
    private final ObjectMapper mapper;
    private org.springframework.context.ApplicationEventPublisher events;

    public ProgramAccessService(ProgramAccessPolicyRepository policies, PermissionableResolver resolver,
                                 ContentItemRepository contents, GeoResolver geoResolver, ObjectMapper mapper) {
        this(policies, resolver, contents, geoResolver, mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProgramAccessService(ProgramAccessPolicyRepository policies, PermissionableResolver resolver,
                                 ContentItemRepository contents, GeoResolver geoResolver, ObjectMapper mapper,
                                 org.springframework.context.ApplicationEventPublisher events) {
        this.policies = policies;
        this.resolver = resolver;
        this.contents = contents;
        this.geoResolver = geoResolver;
        this.mapper = mapper;
        this.events = events;
    }

    /** 授权门禁：社区版无 License 中间件，条件访问的开放范围由管理端入口决定。 */

    public record Window(Instant from, Instant to) {}

    public record GeoList(List<String> countries, List<String> provinces) {
        boolean restricted() {
            return (countries != null && !countries.isEmpty()) || (provinces != null && !provinces.isEmpty());
        }
    }

    public record Side(String ip, boolean ipDefined, GeoList geo, boolean geoDefined,
                       List<Window> windows, boolean timeDefined, Boolean download, String source,
                       String downloadSource) {

        /** 兼容既有构造：未记录下载来源时按 null（未知来源）处理。 */
        public Side(String ip, boolean ipDefined, GeoList geo, boolean geoDefined,
                    List<Window> windows, boolean timeDefined, Boolean download, String source) {
            this(ip, ipDefined, geo, geoDefined, windows, timeDefined, download, source, null);
        }
    }

    public record Effective(Side folder, Side category) {
        public boolean downloadEnabled() {
            return Boolean.TRUE.equals(downloadOpinion());
        }

        /**
         * 下载总闸的「单侧意见」：null 表示这条链上没有任何一层表态（未配置）。
         * 与 {@link #downloadEnabled()} 的区别是保留 null，供媒体原件与引用内容合并时
         * 区分「明确关闭」与「未表态」—— 否则栏目里打开的开关永远会被对象自身的空链压成关闭。
         */
        public Boolean downloadOpinion() {
            Boolean left = folder == null ? null : folder.download();
            Boolean right = category == null ? null : category.download();
            if (left == null) return right;
            if (right == null) return left;
            return left && right;
        }
    }

    /** 下载判定的结果：是否放行 + 生效开关的来源（对象类型码 / {@code REFERENCES} / null=整链未配置）。 */
    public record DownloadDecision(boolean allowed, String source) {}


    private final java.util.concurrent.ConcurrentHashMap<String, Effective> effectiveCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 媒体引用扫描是 O(内容数) 的全表扫描，下载判定按资产缓存一小段时间，避免重复扫描。 */
    private record CachedReferences(List<UUID> contentIds, long expiresAt) {}

    private static final long REFERENCE_TTL_MS = 60_000L;
    private static final int REFERENCE_CACHE_LIMIT = 512;

    private final java.util.concurrent.ConcurrentHashMap<UUID, CachedReferences> referenceCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 媒体引用来源（媒体侧实现）。单测直接构造时为 null，此时媒体只走自身继承链。 */
    private MediaContentScope mediaScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMediaScope(MediaContentScope mediaScope) {
        this.mediaScope = mediaScope;
    }

    public void invalidateAll() {
        effectiveCache.clear();
        referenceCache.clear();
    }

    public Effective effective(String objectType, UUID objectId) {
        if (objectType == null || objectId == null) return loadEffective(objectType, objectId);
        return effectiveCache.computeIfAbsent(objectType + ":" + objectId, key -> loadEffective(objectType, objectId));
    }

    private Effective loadEffective(String objectType, UUID objectId) {
        Side folder = resolveSide(objectType, objectId);
        Side category = null;
        UUID categoryId = categoryIdOf(objectType, objectId);
        if (categoryId != null) category = resolveSide(ObjectType.TAXONOMY, categoryId);
        return new Effective(folder, category);
    }

    /**
     * 对象经由「所属内容」到达的分类（TAXONOMY）。
     *
     * <p>{@code CONTENT} 取自身分类；{@code MEDIA} 取它挂靠的内容项（自动创建的媒体内容项，
     * 或人工归档到栏目/分类的媒体项）的分类。此前只处理 {@code CONTENT}，导致媒体原件
     * 永远拿不到分类链上的 IP/地域/时间窗/下载策略。</p>
     */
    private UUID categoryIdOf(String objectType, UUID objectId) {
        if (contents == null || objectId == null) return null;
        if (ObjectType.CONTENT.equals(objectType)) {
            return contents.findById(objectId).map(ContentItemEntity::getCategoryId).orElse(null);
        }
        if (!ObjectType.MEDIA.equals(objectType) || resolver == null) return null;
        PermissionableResolver.Ref parent = resolver.parent(ObjectType.MEDIA, objectId);
        if (parent == null || !ObjectType.CONTENT.equals(parent.objectType())) return null;
        return contents.findById(parent.objectId()).map(ContentItemEntity::getCategoryId).orElse(null);
    }

    /**
     * 下载总闸判定（FR-ACCESS-005）。
     *
     * <p>普通对象按自身继承链取更严的一侧，整链未配置视为关闭。**媒体原件另加一层**：
     * 它的内容项是媒体模型自动建的、不在栏目/分类里，所以栏目里打开的下载开关够不到它。
     * 这里把它引用它的内容也纳入判定，规则同样是「更严者胜、未配置不表态」：
     * <ul>
     *   <li>自身链明确 <b>false</b> → 直接关闭（不必扫描引用）；</li>
     *   <li>其余情况与每条引用内容的有效开关合并，任一侧 false 即关闭；</li>
     *   <li>全部未配置 → 关闭（保持「默认不可下载」）。</li>
     * </ul>
     */
    public DownloadDecision resolveDownload(String objectType, UUID objectId) {
        Effective effect = effective(objectType, objectId);
        Boolean own = effect.downloadOpinion();
        String ownSource = downloadSourceOf(effect);
        if (!ObjectType.MEDIA.equals(objectType) || mediaScope == null) {
            return new DownloadDecision(Boolean.TRUE.equals(own), ownSource);
        }
        if (Boolean.FALSE.equals(own)) return new DownloadDecision(false, ownSource);

        boolean allowed = Boolean.TRUE.equals(own);
        String source = own == null ? null : ownSource;
        for (UUID contentId : referencingContents(objectId)) {
            Boolean opinion = effective(ObjectType.CONTENT, contentId).downloadOpinion();
            if (opinion == null) continue;
            if (source == null) source = "REFERENCES";
            if (!opinion) return new DownloadDecision(false, "REFERENCES");
            allowed = true;
        }
        return new DownloadDecision(allowed, source);
    }

    private List<UUID> referencingContents(UUID mediaId) {
        if (mediaId == null) return List.of();
        long now = System.currentTimeMillis();
        CachedReferences hit = referenceCache.get(mediaId);
        if (hit != null && hit.expiresAt() > now) return hit.contentIds();
        if (referenceCache.size() >= REFERENCE_CACHE_LIMIT) referenceCache.clear();
        List<UUID> ids = mediaScope == null ? List.of() : mediaScope.referencingContents(mediaId);
        if (ids == null) ids = List.of();
        referenceCache.put(mediaId, new CachedReferences(ids, now + REFERENCE_TTL_MS));
        return ids;
    }

    /**
     * 内容变更（含在栏目/分类之间移动）会让按对象缓存的生效策略过期：
     * 不清掉的话，把内容移进一个带 IP / 下载限制的栏目后，旧判定会一直生效到重启
     * —— 受限方向留下的是「放行」，属于必须消除的过期窗口。媒体→内容引用同理。
     */
    @org.springframework.context.event.EventListener
    public void onContentChanged(com.powercms.cms.content.ContentChangedEvent event) {
        if (event == null || event.contentItemId() == null) return;
        effectiveCache.remove(ObjectType.CONTENT + ":" + event.contentItemId());
        referenceCache.clear();
    }

    /** 生效下载开关来自哪一层（沿对象链最近一条明确设置）。 */
    private static String downloadSourceOf(Effective effect) {
        if (effect.folder() != null && effect.folder().downloadSource() != null) {
            return effect.folder().downloadSource();
        }
        if (effect.category() != null && effect.category().downloadSource() != null) {
            return effect.category().downloadSource();
        }
        return null;
    }

    /** 返回拒绝原因码；放行时返回 null。download 为 true 时额外要求下载开关打开。 */
    public String denyReason(String objectType, UUID objectId, String clientIp, Instant now, boolean download) {
        Effective effect = effective(objectType, objectId);
        if (!ipAllows(effect, clientIp)) return ErrorCodes.ACCESS_IP_DENIED;
        if (!geoAllows(effect, clientIp)) return ErrorCodes.ACCESS_IP_DENIED;
        if (!timeAllows(effect, now == null ? Instant.now() : now)) return ErrorCodes.ACCESS_WINDOW_DENIED;
        if (download && !resolveDownload(objectType, objectId).allowed()) return ErrorCodes.DOWNLOAD_DISABLED;
        return null;
    }

    @Transactional
    public ProgramAccessPolicyEntity save(UUID siteId, String objectType, UUID objectId,
                                           String ipAllowlist, GeoList geo, List<Window> windows,
                                           Boolean downloadEnabled, UUID actor) {
        // 条件访问（IP/地域/时间窗/下载控制）在社区版沿用既有实现：
        // 已配置的策略在交付读路径继续生效（读侧放行在安全上更保守）。
        if (!POLICY_TYPES.contains(objectType)) {
            throw new BusinessException(ErrorCodes.ACCESS_POLICY_INVALID, "该对象类型不能配置访问策略");
        }
        if (ipAllowlist != null && !CidrMatcher.isWellFormed(ipAllowlist)) {
            throw new BusinessException(ErrorCodes.ACCESS_POLICY_INVALID, "IP 段格式无效");
        }
        if (windows != null) {
            for (Window window : windows) {
                if (window == null || window.from() == null || window.to() == null || !window.from().isBefore(window.to())) {
                    throw new BusinessException(ErrorCodes.ACCESS_POLICY_INVALID, "时间窗必须以 UTC 表示，且起点早于终点");
                }
            }
        }
        ProgramAccessPolicyEntity row = policies.findByObjectTypeAndObjectId(objectType, objectId)
            .orElseGet(() -> ProgramAccessPolicyEntity.create(siteId, objectType, objectId, actor));
        row.setIpAllowlist(blankToEmpty(ipAllowlist));
        row.setGeoAllowlist(geo == null ? null : writeGeo(geo));
        row.setTimeWindows(windows == null ? null : writeWindows(windows));
        row.setDownloadEnabled(downloadEnabled);
        row.setUpdatedAt(Instant.now());
        ProgramAccessPolicyEntity saved = policies.save(row);
        effectiveCache.remove(objectType + ":" + objectId);
        if (events != null && siteId != null) {
            events.publishEvent(new AccessControlChangedEvent(siteId, objectType, objectId));
        }
        return saved;
    }

    /** 生效策略是否依赖客户端 IP 或当前时刻。这种响应不能进共享缓存。 */
    public boolean variesByRequest(String objectType, UUID objectId) {
        Effective effect = effective(objectType, objectId);
        return varies(effect.folder()) || varies(effect.category());
    }

    private static boolean varies(Side side) {
        if (side == null) return false;
        if (side.ipDefined() && side.ip() != null && !side.ip().isBlank()) return true;
        if (side.geoDefined() && side.geo() != null && side.geo().restricted()) return true;
        return side.timeDefined() && side.windows() != null && !side.windows().isEmpty();
    }

    public ProgramAccessPolicyEntity direct(String objectType, UUID objectId) {
        return policies.findByObjectTypeAndObjectId(objectType, objectId).orElse(null);
    }

    private Side resolveSide(String objectType, UUID objectId) {
        String ip = null;
        boolean ipDefined = false;
        GeoList geo = null;
        boolean geoDefined = false;
        List<Window> windows = null;
        boolean timeDefined = false;
        Boolean download = null;
        String downloadSource = null;
        String source = null;
        PermissionableResolver.Ref cursor = new PermissionableResolver.Ref(objectType, objectId);
        int guard = 0;
        while (cursor != null && guard++ < 12) {
            ProgramAccessPolicyEntity row = policies.findByObjectTypeAndObjectId(cursor.objectType(), cursor.objectId()).orElse(null);
            if (row != null) {
                if (!ipDefined && row.getIpAllowlist() != null) {
                    ip = row.getIpAllowlist();
                    ipDefined = true;
                    source = cursor.objectType();
                }
                if (!geoDefined && row.getGeoAllowlist() != null) {
                    geo = readGeo(row.getGeoAllowlist());
                    geoDefined = true;
                }
                if (!timeDefined && row.getTimeWindows() != null) {
                    windows = readWindows(row.getTimeWindows());
                    timeDefined = true;
                }
                if (download == null && row.getDownloadEnabled() != null) {
                    download = row.getDownloadEnabled();
                    // 单独记录来源：只设了下载开关的策略行不会填 source（那是 IP 维度的来源），
                    // 界面要显示「当前可下载（继承自站点）」就必须有这一项。
                    downloadSource = cursor.objectType();
                }
            }
            if (ipDefined && geoDefined && timeDefined && download != null) break;
            cursor = resolver.parent(cursor.objectType(), cursor.objectId());
        }
        return new Side(ip, ipDefined, geo, geoDefined, windows == null ? List.of() : windows, timeDefined,
            download, source, downloadSource);
    }

    private static boolean ipAllows(Effective effect, String clientIp) {
        return sideIp(effect.folder(), clientIp) && sideIp(effect.category(), clientIp);
    }

    private static boolean sideIp(Side side, String clientIp) {
        if (side == null || !side.ipDefined() || side.ip() == null || side.ip().isBlank()) return true;
        return CidrMatcher.isAllowed(side.ip(), clientIp);
    }

    private boolean geoAllows(Effective effect, String clientIp) {
        boolean folderRestricted = effect.folder() != null && effect.folder().geoDefined() && effect.folder().geo() != null && effect.folder().geo().restricted();
        boolean categoryRestricted = effect.category() != null && effect.category().geoDefined() && effect.category().geo() != null && effect.category().geo().restricted();
        if (!folderRestricted && !categoryRestricted) return true;
        GeoResolver.Place place = geoResolver.locate(clientIp).orElse(null);
        if (place == null) return false;
        if (folderRestricted && !geoHit(effect.folder().geo(), place)) return false;
        return !categoryRestricted || geoHit(effect.category().geo(), place);
    }

    private static boolean geoHit(GeoList list, GeoResolver.Place place) {
        if (list.countries() != null && place.country() != null && list.countries().stream().anyMatch(c -> c.equalsIgnoreCase(place.country()))) {
            return true;
        }
        return list.provinces() != null && place.province() != null
            && list.provinces().stream().anyMatch(p -> p.equalsIgnoreCase(place.province()));
    }

    private static boolean timeAllows(Effective effect, Instant now) {
        return sideTime(effect.folder(), now) && sideTime(effect.category(), now);
    }

    private static boolean sideTime(Side side, Instant now) {
        if (side == null || !side.timeDefined() || side.windows() == null || side.windows().isEmpty()) return true;
        for (Window window : side.windows()) {
            if (!now.isBefore(window.from()) && now.isBefore(window.to())) return true;
        }
        return false;
    }

    private static String blankToEmpty(String value) {
        if (value == null) return null;
        return value.trim();
    }

    private String writeGeo(GeoList geo) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.set("countries", mapper.valueToTree(geo.countries() == null ? List.of() : geo.countries()));
            node.set("provinces", mapper.valueToTree(geo.provinces() == null ? List.of() : geo.provinces()));
            return mapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.ACCESS_POLICY_INVALID, "地域列表无法保存");
        }
    }

    public GeoList readGeo(String json) {
        if (json == null || json.isBlank()) return new GeoList(List.of(), List.of());
        try {
            JsonNode node = mapper.readTree(json);
            return new GeoList(textList(node.get("countries")), textList(node.get("provinces")));
        } catch (Exception ex) {
            return new GeoList(List.of(), List.of());
        }
    }

    private String writeWindows(List<Window> windows) {
        try {
            List<ObjectNode> rows = new ArrayList<>();
            for (Window window : windows) {
                ObjectNode node = mapper.createObjectNode();
                node.put("from", window.from().toString());
                node.put("to", window.to().toString());
                rows.add(node);
            }
            return mapper.writeValueAsString(rows);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.ACCESS_POLICY_INVALID, "时间窗无法保存");
        }
    }

    public List<Window> readWindows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) return List.of();
            List<Window> windows = new ArrayList<>();
            for (JsonNode item : node) {
                windows.add(new Window(Instant.parse(item.path("from").asText()), Instant.parse(item.path("to").asText())));
            }
            return windows;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.asText("").isBlank()) values.add(item.asText());
        });
        return values;
    }
}
