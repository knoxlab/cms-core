package com.powercms.cms.model.repo;

import com.powercms.cms.model.entity.ContentTypeEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContentTypeRepository extends JpaRepository<ContentTypeEntity, UUID> {
  List<ContentTypeEntity> findBySiteIdOrderByCodeAsc(UUID siteId);

  /** 非废弃（DRAFT/PUBLISHED）类型，用于内容创建入口与内容菜单。 */
  @Query("select ct from ContentTypeEntity ct where ct.siteId = ?1 and ct.status <> 'DEPRECATED' order by ct.code asc")
  List<ContentTypeEntity> findActiveBySiteIdOrderByCodeAsc(UUID siteId);

  boolean existsBySiteIdAndCodeAndStatus(UUID siteId, String code, String status);

  boolean existsBySiteIdAndCode(UUID siteId, String code);

  /** 归属校验：数据源落点等内容模型引用必须是本站点的类型（FR-AI-SRC-001 站点隔离）。 */
  boolean existsBySiteIdAndId(UUID siteId, UUID id);

  List<ContentTypeEntity> findBySiteIdAndCodeOrderByVersionAsc(UUID siteId, String code);

  Optional<ContentTypeEntity> findBySiteIdAndCodeAndVersion(UUID siteId, String code, int version);

  List<ContentTypeEntity> findByParentTypeId(UUID parentTypeId);

  /** 非废弃且开启快速创建的类型。 */
  @Query("select ct from ContentTypeEntity ct where ct.siteId = ?1 and ct.quickCreate = true and ct.status <> 'DEPRECATED' order by ct.name asc")
  List<ContentTypeEntity> findActiveBySiteIdAndQuickCreateTrueOrderByNameAsc(UUID siteId);

  /** 非废弃且开启内容菜单的类型。 */
  @Query("select ct from ContentTypeEntity ct where ct.siteId = ?1 and ct.contentMenuEnabled = true and ct.status <> 'DEPRECATED' order by ct.contentMenuOrder asc, ct.name asc")
  List<ContentTypeEntity> findActiveBySiteIdAndContentMenuEnabledTrueOrderByContentMenuOrderAscNameAsc(UUID siteId);

  List<ContentTypeEntity> findBySiteIdAndObjectTypeOrderByCodeAsc(UUID siteId, String objectType);

  /** 同 code 非 DEPRECATED 的生效行（媒体自动归类候选，避免新资产绑到旧版本）。 */
  @Query("select ct from ContentTypeEntity ct where ct.siteId = ?1 and ct.objectType = ?2 "
      + "and ct.status <> 'DEPRECATED' order by ct.code asc")
  List<ContentTypeEntity> findActiveBySiteIdAndObjectTypeOrderByCodeAsc(UUID siteId, String objectType);

  /** 全部已废弃的 MEDIA 类型行 id（存量内容项重绑用）。 */
  @Query("select ct.id from ContentTypeEntity ct where ct.objectType = 'MEDIA' and ct.status = 'DEPRECATED'")
  List<UUID> findDeprecatedMediaTypeIds();

  /** 同 code 下已发布行（启动补齐用）。 */
  List<ContentTypeEntity> findBySiteIdAndCodeAndStatusOrderByVersionAsc(UUID siteId, String code, String status);

  /** 全部未废弃的文档媒体类型（启动时补齐文档 MIME 白名单）。 */
  @Query("select ct from ContentTypeEntity ct where ct.code = 'media_document' and ct.status <> 'DEPRECATED'")
  List<ContentTypeEntity> findActiveMediaDocuments();
}
