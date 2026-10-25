package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.MemberLibraryEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberLibraryEntryRepository extends JpaRepository<MemberLibraryEntryEntity, UUID> {

    Optional<MemberLibraryEntryEntity> findBySiteIdAndMemberIdAndContentIdAndEntryType(
        UUID siteId, UUID memberId, UUID contentId, String entryType);

    List<MemberLibraryEntryEntity> findTop100BySiteIdAndMemberIdAndEntryTypeOrderByUpdatedAtDesc(
        UUID siteId, UUID memberId, String entryType);

    List<MemberLibraryEntryEntity> findTop20BySiteIdAndMemberIdAndEntryTypeOrderByUpdatedAtDesc(
        UUID siteId, UUID memberId, String entryType);

    List<MemberLibraryEntryEntity> findBySiteIdAndEntryTypeAndContentIdIn(
        UUID siteId, String entryType, Collection<UUID> contentIds);

    long countBySiteIdAndContentIdAndEntryType(UUID siteId, UUID contentId, String entryType);

    /** 删除内容时级联清理收藏/点赞/浏览等会员标记。 */
    void deleteBySiteIdAndContentId(UUID siteId, UUID contentId);
}
