package com.powercms.cms.identity.permission.repo;

import com.powercms.cms.identity.permission.entity.ObjectAclEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ObjectAclRepository extends JpaRepository<ObjectAclEntity, UUID> {

    List<ObjectAclEntity> findByObjectTypeAndObjectId(String objectType, UUID objectId);

    Optional<ObjectAclEntity> findByObjectTypeAndObjectIdAndPrincipalTypeAndPrincipalId(
        String objectType, UUID objectId, String principalType, UUID principalId);

    void deleteByObjectTypeAndObjectId(String objectType, UUID objectId);

    boolean existsByObjectTypeAndObjectId(String objectType, UUID objectId);

    /** 对象级 inherit 标志：取任一行（同对象所有行一致；无行视为继承）。 */
    @Query("select e.inherit from ObjectAclEntity e where e.objectType = :objectType and e.objectId = :objectId")
    List<Boolean> inheritFlags(@Param("objectType") String objectType, @Param("objectId") UUID objectId);
}
